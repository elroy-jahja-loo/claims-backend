# Claims backend — local startup

Prerequisites: Docker Desktop with Compose, Bash, Python 3, and OpenSSL. The Java app has not been added yet.

```bash
./start.sh
```

This starts PostgreSQL and single-node Kafka, waits for both, and creates the `claims.notifications.v1` topic. PostgreSQL binds **127.0.0.1:55432** (not your Mac's 5432); Kafka binds **127.0.0.1:29092**. When a Spring Boot Maven/Gradle wrapper is added, the same command will also run the app in the foreground. Use `./start.sh --infra-only` to start just the services.

The existing `.env` is never overwritten. If absent, startup creates an ignored `.env` with a random local database password. If `.env` exists without a password, startup leaves it untouched and creates an ignored `.env.local` for the missing password. Both files are local-only; do not commit credentials. Optional `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_HOST_PORT`, and `KAFKA_HOST_PORT` can be set in `.env` before the first start. For an existing database volume, changing the password in the env file does **not** change PostgreSQL's initialized user password.

Verify an existing stack (checks a PostgreSQL query and describes the topic):

```bash
./start.sh --verify
```

Stop without deleting the database or Kafka data:

```bash
./start.sh --stop
```

When the app exists, its host-side defaults from `start.sh` are `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/claims` and `SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:29092`; the script also passes the local database username and password privately. Services communicate inside Compose over `postgres:5432` and `kafka:9092`. These are local developer settings, not production security defaults.
