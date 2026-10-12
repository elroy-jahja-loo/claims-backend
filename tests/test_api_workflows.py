"""Real HTTP/Kafka acceptance tests; no app internals or direct SQL.

Run: CLAIMS_TEST_PASSWORD=<secret> python3 -m unittest discover -s tests -p 'test_api_*.py' -v
The future test-only app profile must load fixtures.json; until the app exists,
preflight fails rather than claiming any workflow passed. Actor passwords may
override the shared secret with CLAIMS_TEST_<ACTOR>_PASSWORD.
Install the Kafka test client with: python3 -m pip install -r tests/requirements.txt
"""

import concurrent.futures
from decimal import Decimal
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
FIX = json.loads((ROOT / "tests/fixtures.json").read_text())
URL = os.getenv("CLAIMS_API_URL", "http://127.0.0.1:8080").rstrip("/")


def token():
    return uuid.uuid4().hex


class HTTP:
    def __init__(self, email=None):
        self.email = email
        self.csrf = None
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, *, idem=None, csrf=True):
        request = urllib.request.Request(URL + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        request.add_header("Accept", "application/json")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        if idem:
            request.add_header("Idempotency-Key", idem)
        if csrf and self.csrf and method not in ("GET", "HEAD"):
            request.add_header("X-CSRF-Token", self.csrf)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None, response.headers

    def login(self, password):
        self.csrf = self.call("GET", "/api/csrf")[1]["csrfToken"]
        result = self.call("POST", "/api/session", {"email": self.email, "password": password})
        if result[0] == 200:
            self.csrf = result[1]["csrfToken"]
        return result


def report_body(kind="MOTOR", **extra):
    return {"type": kind, "incident": {"date": "2026-10-12", "location": "Singapore",
                                   "description": "Test loss", "affectedAsset": "Car" if kind == "MOTOR" else "Home"},
            "policyReference": None, "requestedAmount": None, **extra}


def assessment(loss="3400.00", estimate="2900.00"):
    return {"coverage": "APPEARS_COVERED", "reason": "Appears covered",
            "assessedLoss": loss, "estimatedUnpaidAmount": estimate}


class TestAPI(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            HTTP().call("GET", "/api/csrf")
        except urllib.error.URLError as error:
            raise RuntimeError(f"No application at {URL}; PostgreSQL/Kafka alone cannot run API tests: {error}") from error
        cls.password = os.getenv("CLAIMS_TEST_PASSWORD")
        accounts = {}
        for name, fixture in FIX["accounts"].items():
            password = os.getenv(f"CLAIMS_TEST_{name}_PASSWORD") or cls.password
            if not password:
                raise RuntimeError(f"Set CLAIMS_TEST_PASSWORD or CLAIMS_TEST_{name}_PASSWORD; no secret is committed")
            client = HTTP(fixture["email"])
            code, body, _ = client.login(password)
            if code != 200:
                raise RuntimeError(f"Test profile has not provisioned {name} ({fixture['email']}): login HTTP {code}")
            account = body["account"]
            if account["role"] != fixture["role"]:
                raise RuntimeError(f"Wrong role for test fixture {name}")
            if fixture["role"] == "CLAIMANT":
                expected = {"code": fixture["market"], "currency": FIX["markets"][fixture["market"]]["currency"]}
                if account["market"] != expected:
                    raise RuntimeError(f"Wrong market/currency for test fixture {name}")
            accounts[name] = account
        if accounts["O1"]["teamId"] != accounts["O2"]["teamId"] or accounts["O1"]["teamId"] == accounts["O3"]["teamId"]:
            raise RuntimeError("Test fixtures require O1/O2 on one team and O3 on another")
        cls.primary_team = accounts["O1"]["teamId"]

    def setUp(self):
        self.a, self.o1, self.o2 = (self.actor(n) for n in ("A", "O1", "O2"))

    def actor(self, name):
        client = HTTP(FIX["accounts"][name]["email"])
        self.ok(client.login(os.getenv(f"CLAIMS_TEST_{name}_PASSWORD") or self.password), 200)
        return client

    def ok(self, result, code, error=None):
        self.assertEqual(result[0], code, result[1])
        if error:
            self.assertEqual(result[1]["error"]["code"], error)
        return result[1]

    def report(self, actor=None, kind="MOTOR"):
        result = (actor or self.a).call("POST", "/api/claims", report_body(kind), idem=token())
        claim = self.ok(result, 201)["claim"]
        self.assertEqual(result[2].get("Location"), f'/api/claims/{claim["id"]}')
        return claim

    def get(self, actor, claim_id):
        return self.ok(actor.call("GET", f"/api/claims/{claim_id}"), 200)["claim"]

    def activity(self, actor, claim_id):
        return self.ok(actor.call("GET", f"/api/claims/{claim_id}/activity"), 200)["items"]

    def pickup(self, actor, claim_id):
        return self.ok(actor.call("POST", f"/api/claims/{claim_id}/pickup", {}), 200)["claim"]

    def assess(self, actor, claim_id, loss="3400.00", estimate="2900.00"):
        return self.ok(actor.call("PUT", f"/api/claims/{claim_id}/assessment", assessment(loss, estimate)), 200)["claim"]

    def decide(self, actor, claim_id, kind="SETTLED", amount="2900.00", version=None, idem=None):
        version = self.get(actor, claim_id)["version"] if version is None else version
        return actor.call("POST", f"/api/claims/{claim_id}/decision",
                          {"kind": kind, "amount": amount, "reason": "Reasoned outcome", "reviewedVersion": version},
                          idem=idem or token())

    def summary(self):
        return self.ok(self.o1.call("GET", "/api/officer/team/summary?from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"), 200)

    def notices(self, actor, claim_id, kind):
        items = self.ok(actor.call("GET", "/api/notifications?limit=100"), 200)["items"]
        for item in items:
            self.assertEqual(set(item), {"id", "type", "claimId", "claimReference", "occurredAt"})
        return [item for item in items if item["claimId"] == claim_id and item["type"] == kind]

    def await_notice(self, actor, claim_id, kind):
        until = time.monotonic() + 40
        while time.monotonic() < until:
            matches = self.notices(actor, claim_id, kind)
            if matches:
                self.assertEqual(len(matches), 1)
                return matches[0]
            time.sleep(0.4)
        self.fail(f"No {kind} notice for {claim_id}")

    @staticmethod
    def race(*actions):
        barrier = threading.Barrier(len(actions))
        def run(action):
            barrier.wait(timeout=10)
            return action()
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(actions)) as pool:
            futures = [pool.submit(run, a) for a in actions]
            return [future.result(timeout=25) for future in futures]

    def test_report_settle_handover_pay_and_reject(self):
        baseline = self.summary()
        claim = self.report()
        cid = claim["id"]
        self.assertEqual((claim["market"], claim["currency"], claim["status"], claim["requestedAmount"]),
                         ("SG", "SGD", "REPORTED", None))
        self.assertIn(cid, [c["id"] for c in self.ok(self.o1.call("GET", "/api/officer/claims?view=unassigned"), 200)["items"]])
        self.pickup(self.o1, cid)
        self.ok(self.decide(self.o1, cid), 409, "MISSING_ASSESSMENT")
        self.assess(self.o1, cid)
        settled = self.ok(self.decide(self.o1, cid), 200)["claim"]
        self.assertEqual((settled["status"], settled["payment"]["status"], settled["decision"]["amount"]),
                         ("SETTLED", "UNPAID", "2900.00"))
        claimant_detail = self.get(self.a, cid)
        for internal in ("assessment", "exposure", "teamId", "assignedOfficerId"):
            self.assertNotIn(internal, claimant_detail)
        self.assertEqual(self.summary()["openCount"], baseline["openCount"] + 1)
        self.assertEqual(self.summary()["decidedCount"], baseline["decidedCount"] + 1)
        self.assertEqual(self.summary()["decisionCount"], baseline["decisionCount"] + 1)
        self.assertIsNotNone(self.summary()["meanTimeToDecisionSeconds"])
        self.assertEqual(self.currency_total() - self.currency_total(baseline), Decimal("2900.00"))
        self.ok(self.o1.call("POST", f"/api/claims/{cid}/release", {"reason": "Payment handover"}), 200)
        self.pickup(self.o2, cid)
        paid = self.ok(self.o2.call("POST", f"/api/claims/{cid}/mark-paid", {}), 200)
        self.assertEqual(self.ok(self.o2.call("POST", f"/api/claims/{cid}/mark-paid", {}), 200), paid)
        self.ok(self.o1.call("POST", f"/api/claims/{cid}/mark-paid", {}), 403, "FORBIDDEN")
        claimant_detail = self.get(self.a, cid)
        self.assertEqual(claimant_detail["payment"]["status"], "PAID")
        self.assertNotIn("confirmedByOfficerId", claimant_detail["payment"])
        self.assertEqual(sum(e["type"] == "MARK_PAID" for e in self.activity(self.o2, cid)), 1)
        self.assertEqual(self.summary()["openCount"], baseline["openCount"])
        self.assertEqual(self.summary()["resolvedCount"], baseline["resolvedCount"] + 1)
        self.assertEqual(self.currency_total(), self.currency_total(baseline))
        self.await_notice(self.a, cid, "DECIDED")

        rejected_id = self.report(kind="PROPERTY")["id"]
        self.pickup(self.o1, rejected_id)
        rejected = self.ok(self.decide(self.o1, rejected_id, kind="REJECTED", amount=None), 200)["claim"]
        self.assertEqual(rejected["status"], "REJECTED")
        self.assertIsNone(rejected["assessment"]["assessedLoss"])
        self.assertEqual(self.summary()["decidedCount"], baseline["decidedCount"] + 2)
        self.assertEqual(self.summary()["resolvedCount"], baseline["resolvedCount"] + 2)
        self.assertEqual(self.summary()["decisionCount"], baseline["decisionCount"] + 2)
        self.ok(self.decide(self.o1, rejected_id, kind="REJECTED", amount=None), 409, "STATE_CONFLICT")
        self.await_notice(self.a, rejected_id, "DECIDED")

    def currency_total(self, summary=None):
        rows = (summary or self.summary())["exposureByCurrency"]
        return Decimal(next((r["knownOutstandingTotal"] for r in rows if r["currency"] == "SGD"), "0.00"))

    def test_login_access_and_safe_claimant_feed(self):
        bad = HTTP(FIX["accounts"]["A"]["email"])
        self.ok(bad.login("incorrect-" + token()), 401, "BAD_CREDENTIALS")
        self.ok(bad.call("GET", "/api/session"), 401, "AUTH_REQUIRED")
        claim_id = self.report()["id"]
        self.pickup(self.o1, claim_id)
        b, o3 = self.actor("B"), self.actor("O3")
        for client in (b, o3):
            self.ok(client.call("GET", f"/api/claims/{claim_id}"), 404, "NOT_FOUND")
        self.ok(HTTP().call("GET", f"/api/claims/{claim_id}"), 401, "AUTH_REQUIRED")
        self.ok(self.o2.call("PUT", f"/api/claims/{claim_id}/assessment", assessment()), 403, "FORBIDDEN")
        self.ok(o3.call("POST", f"/api/claims/{claim_id}/pickup", {}), 404, "NOT_FOUND")
        self.assess(self.o1, claim_id)
        self.ok(self.o1.call("POST", f"/api/claims/{claim_id}/release", {"reason": "Shift handover"}), 200)
        for event in self.activity(self.a, claim_id):
            self.assertEqual(set(event), {"type", "occurredAt", "message"})
            self.assertIn(event["type"], {"CLAIM_REPORTED", "INFORMATION_REQUESTED", "INFORMATION_RECEIVED",
                                          "REQUEST_WITHDRAWN", "DECISION_AVAILABLE", "PAYMENT_CONFIRMED"})
            self.assertNotIn("Shift handover", event["message"])
        self.assertTrue(any(e["details"].get("reason") == "Shift handover"
                            for e in self.activity(self.o2, claim_id)))
        fresh = self.actor("A")
        before = self.ok(fresh.call("GET", "/api/claims?limit=100"), 200)
        self.ok(fresh.call("POST", "/api/claims", report_body(), idem=token(), csrf=False), 403, "CSRF_INVALID")
        self.assertEqual(self.ok(fresh.call("GET", "/api/claims?limit=100"), 200), before)
        self.ok(fresh.call("DELETE", "/api/session"), 204)
        self.ok(fresh.call("GET", "/api/claims"), 401, "AUTH_REQUIRED")

    def test_pickup_and_two_substantive_races(self):
        cid = self.report()["id"]
        outcomes = self.race(lambda: self.o1.call("POST", f"/api/claims/{cid}/pickup", {}),
                             lambda: self.o2.call("POST", f"/api/claims/{cid}/pickup", {}))
        self.assertEqual(sorted(r[0] for r in outcomes), [200, 409])
        self.ok(next(r for r in outcomes if r[0] == 409), 409, "ALREADY_ASSIGNED")
        self.assertEqual(sum(e["type"] == "PICKUP" for e in self.activity(self.o1, cid)), 1)

        first_id = self.report()["id"]
        self.pickup(self.o1, first_id)
        second_o1 = self.actor("O1")
        previous = len(self.activity(self.o1, first_id))
        result = self.race(lambda: self.o1.call("POST", f"/api/claims/{first_id}/release", {"reason": None}),
                           lambda: second_o1.call("PUT", f"/api/claims/{first_id}/assessment", assessment()))
        self.assertIn(sorted(r[0] for r in result), ([200, 403], [200, 400]))
        current = self.get(self.o1, first_id)
        self.assertEqual(len(self.activity(self.o1, first_id)), previous + 1)
        if current["assignedOfficerId"] is None:
            self.ok(result[1], 403, "FORBIDDEN")
            self.assertIsNone(current["assessment"]["assessedLoss"])
        else:
            self.ok(result[0], 400, "REASON_REQUIRED")
            self.assertEqual(current["assessment"]["assessedLoss"], "3400.00")

    def test_request_release_answer_withdrawal_and_race(self):
        cid = self.report()["id"]
        self.pickup(self.o1, cid)
        self.ok(self.o1.call("POST", f"/api/claims/{cid}/release", {"reason": None}), 200)
        self.pickup(self.o2, cid)
        path = f"/api/claims/{cid}/information-requests"
        req = self.ok(self.o2.call("POST", path, {"question": "Please provide a quote"}, idem=token()), 201)["request"]
        self.ok(self.decide(self.o2, cid), 409, "OPEN_REQUEST")
        self.ok(self.o2.call("POST", f"/api/claims/{cid}/release", {"reason": " "}), 400, "REASON_REQUIRED")
        self.ok(self.o2.call("POST", f"/api/claims/{cid}/release", {"reason": "Shift handover"}), 200)
        old_version = self.get(self.o1, cid)["version"]
        answer_path = f'{path}/{req["id"]}/answer'
        self.ok(self.actor("B").call("POST", answer_path, {"answer": "Wrong claimant"}, idem=token()), 404, "NOT_FOUND")
        answered = self.ok(self.a.call("POST", answer_path,
                                       {"answer": "Garage quote", "requestedAmount": "4200.00"}, idem=token()), 200)
        immediately_after_answer = self.get(self.o1, cid)
        self.assertGreater(answered["claim"]["version"], old_version)
        self.assertEqual(immediately_after_answer["version"], answered["claim"]["version"])
        self.assertEqual(immediately_after_answer["status"], "UNDER_REVIEW")
        self.assertEqual(immediately_after_answer["requestedAmount"], "4200.00")
        self.assertIsNone(immediately_after_answer["assignedOfficerId"])
        self.await_notice(self.a, cid, "REQUESTED")
        self.await_notice(self.o1, cid, "ANSWERED")
        self.pickup(self.o1, cid)
        self.assess(self.o1, cid)
        stale = self.decide(self.o1, cid, version=old_version)
        self.ok(stale, 409, "STALE_VERSION")
        self.assertEqual(stale[1]["error"]["currentVersion"], self.get(self.o1, cid)["version"])
        self.assertIsNone(self.get(self.a, cid)["decision"])
        self.ok(self.decide(self.o1, cid), 200)

        race_id = self.report()["id"]
        self.pickup(self.o1, race_id)
        race_path = f"/api/claims/{race_id}/information-requests"
        rid = self.ok(self.o1.call("POST", race_path, {"question": "Quote?"}, idem=token()), 201)["request"]["id"]
        previous = len(self.activity(self.o1, race_id))
        outcome = self.race(lambda: self.a.call("POST", f"{race_path}/{rid}/answer",
                                                {"answer": "Quote given", "requestedAmount": "4200.00"}, idem=token()),
                            lambda: self.o1.call("POST", f"{race_path}/{rid}/withdraw", {"reason": "Not needed"}))
        self.assertEqual(sorted(r[0] for r in outcome), [200, 409])
        self.ok(next(r for r in outcome if r[0] == 409), 409, "STATE_CONFLICT")
        current = self.get(self.o1, race_id)
        self.assertEqual(len(self.activity(self.o1, race_id)), previous + 1)
        if current["informationRequests"][0]["status"] == "ANSWERED":
            self.assertEqual(current["requestedAmount"], "4200.00")
            self.await_notice(self.o1, race_id, "ANSWERED")
        else:
            self.assertEqual(current["informationRequests"][0]["status"], "WITHDRAWN")
            self.assertIsNone(current["requestedAmount"])
            self.assertFalse(self.notices(self.o1, race_id, "ANSWERED"))
        followup = self.ok(self.o1.call("POST", race_path, {"question": "One follow-up"}, idem=token()), 201)["request"]
        withdrawn = self.ok(self.o1.call("POST", f'{race_path}/{followup["id"]}/withdraw',
                                         {"reason": "No longer needed"}), 200)["request"]
        self.assertEqual(withdrawn["status"], "WITHDRAWN")
        self.assertIn("REQUEST_WITHDRAWN", [e["type"] for e in self.activity(self.a, race_id)])

    def test_retries_and_currency_summary(self):
        original, idem = report_body(), token()
        first = self.a.call("POST", "/api/claims", original, idem=idem)
        self.ok(first, 201)
        cid = first[1]["claim"]["id"]
        self.assertEqual(first[2].get("Location"), f"/api/claims/{cid}")
        replay = self.a.call("POST", "/api/claims", original, idem=idem)
        self.assertEqual(replay[:2], first[:2])
        self.assertEqual(replay[2].get("Location"), first[2].get("Location"))
        self.ok(self.a.call("POST", "/api/claims", report_body(policyReference="changed"), idem=idem),
                409, "IDEMPOTENCY_CONFLICT")
        self.assertEqual(len(self.activity(self.o1, cid)), 1)
        self.pickup(self.o1, cid)
        path, req_key = f"/api/claims/{cid}/information-requests", token()
        request = self.o1.call("POST", path, {"question": "A quote?"}, idem=req_key)
        self.ok(request, 201)
        answer_path = f'{path}/{request[1]["request"]["id"]}/answer'
        self.assertEqual(request[2].get("Location"), f'{path}/{request[1]["request"]["id"]}')
        request_activity = len(self.activity(self.o1, cid))
        request_replay = self.o1.call("POST", path, {"question": "A quote?"}, idem=req_key)
        self.assertEqual(request_replay[:2], request[:2])
        self.assertEqual(request_replay[2].get("Location"), request[2].get("Location"))
        self.assertEqual(len(self.activity(self.o1, cid)), request_activity)
        answer_key = token()
        answer = self.a.call("POST", answer_path, {"answer": "Quote provided"}, idem=answer_key)
        self.ok(answer, 200)
        answer_activity = len(self.activity(self.o1, cid))
        self.assertEqual(self.a.call("POST", answer_path, {"answer": "Quote provided"}, idem=answer_key)[:2], answer[:2])
        self.assertEqual(len(self.activity(self.o1, cid)), answer_activity)
        self.assess(self.o1, cid)
        version = self.get(self.o1, cid)["version"]
        decision = {"kind": "SETTLED", "amount": "2900.00", "reason": "Reasoned outcome", "reviewedVersion": version}
        decision_key = token()
        decided = self.o1.call("POST", f"/api/claims/{cid}/decision", decision, idem=decision_key)
        self.ok(decided, 200)
        self.ok(self.o1.call("POST", f"/api/claims/{cid}/mark-paid", {}), 200)
        decision_activity = len(self.activity(self.o1, cid))
        self.assertEqual(self.o1.call("POST", f"/api/claims/{cid}/decision", decision, idem=decision_key)[:2], decided[:2])
        self.assertEqual(len(self.activity(self.o1, cid)), decision_activity)
        self.assertEqual(self.get(self.a, cid)["payment"]["status"], "PAID")
        for actor, kind in ((self.a, "REQUESTED"), (self.o1, "ANSWERED"), (self.a, "DECIDED")):
            self.await_notice(actor, cid, kind)

        before = self.summary()
        for estimate in (None, "0.00", "700.00"):
            claim = self.report()
            self.pickup(self.o1, claim["id"])
            if estimate is not None:
                self.assess(self.o1, claim["id"], loss=None, estimate=estimate)
        after = self.summary()
        self.assertEqual(self.currency_total(after) - self.currency_total(before), Decimal("700.00"))
        self.assertEqual(after["openCount"] - before["openCount"], 3)
        old_unknown = next((r["unknownClaimCount"] for r in before["exposureByCurrency"] if r["currency"] == "SGD"), 0)
        new_sgd = next(r for r in after["exposureByCurrency"] if r["currency"] == "SGD")
        self.assertEqual(new_sgd["unknownClaimCount"] - old_unknown, 1)
        self.assertTrue(new_sgd["incomplete"])
        self.ok(self.a.call("GET", "/api/officer/team/summary?from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"),
                403, "FORBIDDEN")
        m2 = self.actor("M2")
        fixture = self.report(m2)
        self.assertEqual((fixture["market"], fixture["currency"]), ("M2", "USD"))
        self.assertEqual(self.get(self.o1, fixture["id"])["teamId"], self.primary_team)
        self.assertIn(fixture["id"], [c["id"] for c in
                      self.ok(self.o1.call("GET", "/api/officer/claims?view=unassigned&limit=100"), 200)["items"]])
        self.ok(self.actor("O3").call("GET", f'/api/claims/{fixture["id"]}'), 404, "NOT_FOUND")
        self.assertEqual({r["currency"] for r in self.summary()["exposureByCurrency"]} & {"SGD", "USD"}, {"SGD", "USD"})

    def test_kafka_late_and_duplicate_notices(self):
        from confluent_kafka import Consumer, Producer, TopicPartition

        args = ["docker", "compose", "--env-file", ".env"]
        if (ROOT / ".env.local").exists():
            args += ["--env-file", ".env.local"]
        def compose(*commands):
            return subprocess.run(args + list(commands), cwd=ROOT, capture_output=True,
                                  text=True, check=True, timeout=240 if commands[0] == "up" else 90).stdout

        cid, decided_id = self.report()["id"], self.report()["id"]
        self.pickup(self.o1, cid)
        self.pickup(self.o1, decided_id)
        self.assess(self.o1, decided_id)
        compose("stop", "kafka")
        try:
            path = f"/api/claims/{cid}/information-requests"
            rid = self.ok(self.o1.call("POST", path, {"question": "Details?"}, idem=token()), 201)["request"]["id"]
            self.ok(self.a.call("POST", f"{path}/{rid}/answer", {"answer": "Provided"}, idem=token()), 200)
            self.ok(self.decide(self.o1, decided_id), 200)
            self.assertEqual(self.get(self.a, decided_id)["status"], "SETTLED")
            self.assertFalse(self.notices(self.a, cid, "REQUESTED"))
            self.assertFalse(self.notices(self.o1, cid, "ANSWERED"))
            self.assertFalse(self.notices(self.a, decided_id, "DECIDED"))
        finally:
            compose("up", "-d", "--wait", "--wait-timeout", "180", "kafka")
        self.await_notice(self.a, cid, "REQUESTED")
        self.await_notice(self.o1, cid, "ANSWERED")
        self.await_notice(self.a, decided_id, "DECIDED")

        broker = os.getenv("CLAIMS_TEST_KAFKA_BOOTSTRAP", "127.0.0.1:29092")
        topic = "claims.notifications.v1"
        app_group = "claims-notifications"  # the app's configured Kafka consumer group
        consumer = Consumer({"bootstrap.servers": broker, "group.id": "test-" + token(),
                             "auto.offset.reset": "latest", "enable.auto.commit": False})
        try:
            consumer.subscribe([topic])
            until = time.monotonic() + 15
            while time.monotonic() < until:
                consumer.poll(0.2)
                assigned = consumer.assignment()
                if assigned:
                    latest = [consumer.get_watermark_offsets(p)[1] for p in assigned]
                    if all(offset >= 0 for offset in latest):
                        for p, offset in zip(assigned, latest):
                            consumer.seek(TopicPartition(topic, p.partition, offset))
                        break
            else:
                self.fail("Kafka capture consumer did not reach its latest offset")
            target_id = self.report()["id"]
            self.pickup(self.o1, target_id)
            self.ok(self.o1.call("POST", f"/api/claims/{target_id}/information-requests",
                                 {"question": "Only event in capture window"}, idem=token()), 201)
            notice = self.await_notice(self.a, target_id, "REQUESTED")
            # Match the actual delivered event ID, not an arbitrary record in
            # the topic. Fail explicitly if its encoding is not inspectable by
            # this test fixture (textual or raw UUID in key/value/header).
            event_id = str(notice["id"])
            encodings = {event_id.encode(), event_id.lower().encode(), event_id.replace("-", "").encode()}
            try:
                encodings.add(uuid.UUID(event_id).bytes)
            except ValueError:
                pass
            message = None
            until = time.monotonic() + 20
            while time.monotonic() < until and message is None:
                m = consumer.poll(0.4)
                if m is not None and not m.error():
                    chunks = [m.key() or b"", m.value() or b""] + [value or b"" for _, value in m.headers() or []]
                    if any(encoded in chunk or encoded.lower() in chunk.lower()
                           for encoded in encodings for chunk in chunks):
                        message = m
            self.assertIsNotNone(message, f"Could not identify Kafka event {event_id}; refusing to replay another record")
            ack = []
            producer = Producer({"bootstrap.servers": broker})
            producer.produce(topic, key=message.key(), value=message.value(), headers=message.headers(),
                             on_delivery=lambda error, record: ack.append((error, record)))
            self.assertEqual(producer.flush(20), 0)
            self.assertEqual(len(ack), 1)
            self.assertIsNone(ack[0][0])
            replay = ack[0][1]
            until = time.monotonic() + 30
            while time.monotonic() < until:
                output = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh",
                                 "--bootstrap-server", "localhost:9092", "--describe", "--group", app_group)
                rows = [line.split() for line in output.splitlines()]
                if any(len(r) >= 4 and r[0] == app_group and r[1] == topic
                       and r[2] == str(replay.partition()) and r[3].isdigit()
                       and int(r[3]) > replay.offset() for r in rows):
                    break
                time.sleep(0.5)
            else:
                self.fail("No app consumer committed past the duplicated record")
            self.assertEqual([n["id"] for n in self.notices(self.a, target_id, "REQUESTED")], [notice["id"]])
            self.assertEqual(len(self.activity(self.o1, target_id)), 3)  # report, pickup, request
        finally:
            consumer.close()
