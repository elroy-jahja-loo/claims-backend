"""Separate clean-clone acceptance gate; never run as part of normal API discovery.

Run only in an isolated fresh, committed clone with Docker running:
  CLAIMS_RUN_FRESH_CLONE=1 python3 -m unittest discover -s tests -p 'fresh_clone_gate.py' -v

The Python test runner may be installed; its restricted startup PATH hides
host Java, Python and OpenSSL from ./start.sh itself.
"""

import os
import http.cookiejar
import json
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
PROJECT = "claims-backend"
BASE_URL = os.getenv("CLAIMS_API_URL", "http://127.0.0.1:8080").rstrip("/")
EMAIL_A = json.loads((ROOT / "tests/fixtures.json").read_text())["accounts"]["A"]["email"]


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True)


class TestFreshClone(unittest.TestCase):
    @unittest.skipUnless(os.getenv("CLAIMS_RUN_FRESH_CLONE") == "1", "separate clean-clone gate only")
    def test_start_script_itself_starts_the_app_and_both_services(self):
        for name in (".env", ".env.local"):
            self.assertFalse((ROOT / name).exists(), f"Use a fresh clone without {name}")
        local_dir = ROOT / ".local"
        credential = local_dir / "claimant-a.password"
        self.assertFalse(credential.exists(), "Use a fresh clone without a pre-generated claimant credential")
        volumes = docker("volume", "ls", "--filter", f"name={PROJECT}_", "--format", "{{.Name}}")
        self.assertFalse(volumes.stdout.strip(), "Use a fresh Docker project without existing data volumes")
        before = docker("ps", "--filter", f"label=com.docker.compose.project={PROJECT}", "--format", "{{.ID}}")
        self.assertFalse(before.stdout.strip(), "A Compose container is already running; use an isolated fresh project")
        url = urlsplit(BASE_URL)
        with socket.socket() as connection:
            connection.settimeout(1)
            self.assertNotEqual(connection.connect_ex((url.hostname, url.port or 80)), 0,
                                f"An API is already listening at {BASE_URL}")

        with tempfile.TemporaryDirectory(dir=ROOT / "tests") as limited_path:
            for program in ("docker", "bash", "dirname", "grep", "sleep"):
                target = shutil.which(program)
                self.assertIsNotNone(target, f"Missing startup prerequisite: {program}")
                Path(limited_path, program).symlink_to(target)
            # Docker Desktop may need its own credential helper to pull public images.
            for helper in ("docker-credential-desktop", "docker-credential-osxkeychain"):
                if target := shutil.which(helper):
                    Path(limited_path, helper).symlink_to(target)
            # Normal one-command startup, not a special test-seed profile.
            env = {k: v for k, v in os.environ.items()
                   if not k.startswith(("CLAIMS_TEST_", "CLAIMS_LOCAL_", "POSTGRES_", "SPRING_", "JAVA_"))}
            env["PATH"] = limited_path
            process = subprocess.Popen([str(ROOT / "start.sh")], cwd=ROOT, env=env,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        self.assertEqual(process.returncode, 0, "./start.sh failed")
                    try:
                        with urllib.request.urlopen(BASE_URL + "/api/csrf", timeout=2) as response:
                            if response.status == 200:
                                break
                    except (urllib.error.URLError, ConnectionError, TimeoutError):
                        if process.poll() is not None:
                            self.fail("./start.sh returned before the application was ready")
                    time.sleep(0.5)
                else:
                    self.fail("./start.sh did not make the application ready")

                # Port was closed before invoking start.sh; a successful HTTP
                # response afterward demonstrates the normal command started it.
                verify = subprocess.run([str(ROOT / "start.sh"), "--verify"], cwd=ROOT, env=env,
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(verify.returncode, 0, verify.stderr)
                self.assertIn("PostgreSQL ready", verify.stdout)
                self.assertIn("notification topic present", verify.stdout)

                # Normal startup must generate A's password in a dedicated
                # ignored file and seed the corresponding hashed account.
                self.assertTrue(credential.is_file() and not credential.is_symlink(),
                                "Normal startup must create .local/claimant-a.password")
                self.assertFalse(local_dir.is_symlink())
                for path in (local_dir, credential):
                    mode = stat.S_IMODE(path.stat().st_mode)
                    self.assertEqual(mode & 0o077, 0, f"{path.name} must not be accessible by group/others")
                secret = credential.read_text().rstrip("\n")
                self.assertTrue(secret, "Startup-generated claimant credential is empty")
                browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
                with browser.open(BASE_URL + "/api/csrf") as response:
                    csrf = json.load(response)["csrfToken"]
                request = urllib.request.Request(
                    BASE_URL + "/api/session", method="POST",
                    data=json.dumps({"email": EMAIL_A, "password": secret}).encode(),
                    headers={"Content-Type": "application/json", "X-CSRF-Token": csrf},
                )
                with browser.open(request) as response:
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.load(response)["account"]["market"], {"code": "SG", "currency": "SGD"})
                with browser.open(BASE_URL + "/api/claims") as response:
                    self.assertEqual(response.status, 200)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
