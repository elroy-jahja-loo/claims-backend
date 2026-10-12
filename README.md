# Claims dashboard

Prerequisites: Docker with Compose and Bash. No host Java, Maven, Python or OpenSSL is needed to start the app.

```bash
./start.sh
```

Builds and starts the Spring Boot app, PostgreSQL and single-node Kafka, creates the one-partition `claims.notifications.v1` topic and waits for all three. Open **http://127.0.0.1:8080/**. PostgreSQL and Kafka bind to `127.0.0.1:55432` and `127.0.0.1:29092`. `./start.sh --verify` checks all three; `./start.sh --stop` stops containers **without deleting data**.

On first normal startup, Docker generates local passwords in ignored `.local/`: `claimant-a.password`, `claimant-b.password`, `officer-o1.password`, `officer-o2.password`. Directory mode is `0700`; each file is `0600`. Sign in as `a@local.test`, `b@local.test`, `o1@local.test`, or `o2@local.test` using the corresponding file. Never commit or paste these passwords; startup does not print or rotate them on restart. Startup reconciles the four seeded account password hashes with these files without changing existing claims, even if a local password file had to be regenerated. Normal startup seeds **SG/SGD, one team, four accounts, and no claims**. This workspace's local database was prepared separately with three one-time demo claims at different stages; a fresh clone has none until you report one. While signed in, notices refresh automatically every 5 seconds for both roles; click one to fetch current authorized claim detail. Use Refresh for authoritative claim state, queues and team measures. Polling stops on logout or session expiry.

If neither `.env` nor `.env.local` exists, startup generates an ignored `.env` with a random local database password. It never replaces existing env files. If you already have an empty `.env`, provide `POSTGRES_PASSWORD` through your existing `.env.local` or shell. Local HTTP cookies are **not** transport-encrypted: bind only to loopback and use HTTPS (with secure cookies) before any nonlocal deployment. This is a local demonstration, not an internet-facing deployment.

## Try the dashboard in two browsers (macOS)

1. Start Docker Desktop. Open Terminal in this repository directory and run `./start.sh`. Wait for the application-ready message, then open the URL it prints in **Safari** and **Chrome** (normally `http://127.0.0.1:8080/`). If the app is already running, `./start.sh --verify` checks it instead.
2. In the same Terminal run `open .local`. Finder opens the folder containing the generated passwords. Right-click `claimant-a.password` → **Open With → TextEdit**; copy its contents without editing the file. In **Safari**, sign in as `a@local.test` and paste that password.
3. Open `officer-o1.password` the same way. In **Chrome**, sign in as `o1@local.test` with that password. Keep both browsers open: they have separate login cookies. Two ordinary tabs in the same browser do not. Never paste passwords into chat or commit them. You do not need to open `.env` or `.env.local`.

**Three quick-start examples in this local database:** A's **Reported** motor claim is ready for an officer to pick up. The **Under review** property claim belongs to O1 and already has an assessment; O1 can find it under **Assigned to me** and settle or reject it. A's **Waiting for information** motor claim has an open question and no assigned officer; A can send an answer, and an officer can pick it up afterward. These three were created once through the normal REST workflow, not by application startup. Existing records remain on restart; reporting a claim adds another record.

**Full request and decision:** To start from scratch, A fills **Report a new incident** (a MOTOR or PROPERTY claim, date, location, affected asset and description; policy and requested amount can stay blank). Chrome/O1 clicks **Refresh claims**, finds the reference under **Available**, opens it, clicks **Pick up claim**, enters a question in **Question for claimant**, then clicks **Request information**. Safari/A gets an **Information requested** update without refreshing; clicking it opens the current question. A enters an answer in **Your answer** and clicks **Send answer** (the optional requested amount can be `4200.00`). O1 gets a **Claimant answered** update; clicking it opens the latest claim. O1 fills **Assessment (internal)** with **Appears covered**, a reason, assessed loss `3400.00` and estimated unpaid `2900.00`, then clicks **Save assessment**. Under **Offer settlement**, O1 enters `2900.00` and an explanation and clicks **Settle claim**. A gets **Decision available** and can click it to see the unpaid settlement. O1 clicks **Confirm external payment**; A clicks **Refresh claims** to see payment confirmed (payment does not create a notice).

**Handover and access:** On a separate claim, O1 can request information and use **Release to team queue**, entering a reason after substantive work. A can answer while no officer owns the claim. In Chrome, sign O1 out and sign in as `o2@local.test` using `officer-o2.password` from the same folder; O2 sees the team update, finds the claim under **Available**, and picks it up. A must not see O1's internal release reason. A can also report another PROPERTY claim for O2 to **Reject claim** with a reason, without an assessment. Sign A out in Safari and sign in as `b@local.test` with `claimant-b.password`: B's claim list must not show A's claims. When O2 owns a claim, O1 can find it under **All team claims** and read it, but has no editing controls.

Notices may take longer than five seconds because Kafka delivery is asynchronous. Only notices poll automatically: use **Refresh claims** or click a notice for current claim state, queues and totals. These manual checks do not replace the separate clean-clone startup gate below.

## API tests (separate data/profile)

Use a separate Compose project with empty data volumes for tests; stop a normal app first because both use port 8080. Do not switch profiles on an already seeded database. Supply a test password locally rather than in version control:

```bash
export COMPOSE_PROJECT_NAME=claims-api-tests
CLAIMS_PROFILE=api-test-only CLAIMS_TEST_PASSWORD='<your-local-test-password>' ./start.sh
python3 -m venv .local/test-venv
.local/test-venv/bin/python -m pip install -r tests/requirements.txt
CLAIMS_TEST_PASSWORD='<your-local-test-password>' .local/test-venv/bin/python -m unittest discover -s tests -p 'test_api_*.py' -v
```

The `api-test-only` profile reads `tests/fixtures.json` and provisions A/B/O1/O2, fixture-only O3/M2 and USD routing. The normal profile does not seed these fixture-only entities. The Kafka acceptance test stops/restarts the broker; avoid running it against another user's stack. The separate `fresh_clone_gate.py` requires an **isolated clean committed clone** with no running app or existing volumes; it is not part of API test discovery. Do not interpret `--verify` or the API tests as a fresh-clone gate result.

## Design

PostgreSQL transactions and per-claim row locks own lifecycle, immutable history, exposure, request/evidence races and durable idempotency responses. Team/currency measures query committed claims; missing estimates remain unknown. An outbox relay sends metadata-only events to Kafka; the `claims-notifications` consumer group materializes team/account notices once per event ID and marks delivery in the same database transaction before acknowledging offsets. Failed/undelivered events retry without a cutoff; broker downtime does not block claim actions or reads. Notices may arrive later and appear on the next 5-second poll; only the notice list polls, never claims. Claim reads remain available and authoritative during broker downtime. No WebSockets, browser Kafka access, actual payment transfer or policy verification.
