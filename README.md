# Claims dashboard

Prerequisites: Docker with Compose and Bash. No host Java, Maven, Python or OpenSSL is needed to start the app.

```bash
./start.sh
```

Builds and starts the Spring Boot app, PostgreSQL and single-node Kafka, creates the one-partition `claims.notifications.v1` topic and waits for all three. Open **http://127.0.0.1:8080/**. PostgreSQL and Kafka bind to `127.0.0.1:55432` and `127.0.0.1:29092`. `./start.sh --verify` checks all three; `./start.sh --stop` stops containers **without deleting data**.

On first normal startup, Docker generates local passwords in ignored `.local/`: `claimant-a.password`, `claimant-b.password`, `officer-o1.password`, `officer-o2.password`. Directory mode is `0700`; each file is `0600`. Sign in as `a@local.test`, `b@local.test`, `o1@local.test`, or `o2@local.test` using the corresponding file. Never commit or paste these passwords; startup does not print or rotate them on restart. Only **SG/SGD** and one team routing MOTOR and PROPERTY are seeded normally. The claimant sees their market/currency; officers see their team's work. While signed in, notices refresh automatically every 5 seconds for both roles; click one to fetch current authorized claim detail. Use Refresh for authoritative claim state, queues and team measures. Polling stops on logout or session expiry.

If neither `.env` nor `.env.local` exists, startup generates an ignored `.env` with a random local database password. It never replaces existing env files. If you already have an empty `.env`, provide `POSTGRES_PASSWORD` through your existing `.env.local` or shell. Local HTTP cookies are **not** transport-encrypted: bind only to loopback and use HTTPS (with secure cookies) before any nonlocal deployment. This is a local demonstration, not an internet-facing deployment.

## Try the dashboard in two browsers (macOS)

1. Start Docker Desktop. Open Terminal in this repository directory and run `./start.sh`. Wait for the application-ready message, then open the URL it prints in **Safari** and **Chrome** (normally `http://127.0.0.1:8080/`). If the app is already running, `./start.sh --verify` checks it instead.
2. In the same Terminal run `open .local`. Finder opens the folder containing the generated passwords. Right-click `claimant-a.password` → **Open With → TextEdit**; copy its contents without editing the file. In **Safari**, sign in as `a@local.test` and paste that password.
3. Open `officer-o1.password` the same way. In **Chrome**, sign in as `o1@local.test` with that password. Keep both browsers open: they have separate login cookies. Two ordinary tabs in the same browser do not. Never paste passwords into chat or commit them. You do not need to open `.env` or `.env.local`.

**Request and decision:** In Safari, report a MOTOR claim with a date, location, description and affected asset; leave policy reference and requested amount blank. Note its reference: it should be `REPORTED`, next `OFFICER`, with the amount not provided. In Chrome, click **Refresh**, open that claim under **unassigned**, click **Pick up**, then **Request information** and enter a question. Watch Safari's **Notices** without refreshing: a `REQUESTED` notice should arrive after Kafka delivery and the next five-second poll. Click it to read the current question, then **Answer request**; enter an answer and, if you like, `4200.00` as the requested amount. Chrome should show an `ANSWERED` team notice after polling; click it to read the saved answer. In Chrome, **Assess** as `APPEARS_COVERED` with a reason, assessed loss `3400.00` and estimated unpaid `2900.00`; then **Settle** for `2900.00` with an explanation. Safari should get a `DECIDED` notice and show the unpaid settlement when you click it. Chrome can **Confirm external payment**; Safari must use **Refresh** to see `PAID` and next `NONE` (mark-paid does not create a notice).

**Handover:** Report a separate claim as A. O1 picks it up, requests information and **Releases** it with a typed reason such as `Shift handover`. A answers the open request while the claim is unassigned. In Chrome, sign O1 out and sign in as `o2@local.test` using `officer-o2.password` from the same folder. O2 should see the `ANSWERED` team notice, find the claim under **unassigned** and pick it up. A must not see the internal release reason.

**Rejection and access:** A reports another PROPERTY claim. O2 picks it up and **Rejects** with a reason, without an assessment. A should see `REJECTED`, the explanation and a `DECIDED` notice, not a payment action. To check claimant isolation, sign A out in Safari and sign in as `b@local.test` using `claimant-b.password`; B's list must not show A's claims. While O2 owns the undecided handover claim, O1 may read it in the team list but must have no edit controls (sign O2 out in Chrome and sign O1 back in).

Notices may take longer than five seconds because Kafka delivery is asynchronous. Only notices poll automatically: use **Refresh** or click a notice for current claim state, queues and totals. These manual checks do not replace the separate clean-clone startup gate below.

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
