#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

case "${1:-}" in
  ""|--infra-only|--verify|--stop) ;;
  *) echo "Usage: ./start.sh [--infra-only|--verify|--stop]" >&2; exit 2 ;;
esac

command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }
docker compose version >/dev/null || { echo "Docker Compose is required." >&2; exit 1; }

# Never replace an existing .env. Keep a persistent, ignored fallback when it
# exists but does not contain a password (including the initially empty file).
if [[ ! -e .env ]]; then
  umask 077
  if [[ -e .env.local ]]; then
    printf '# Local database password is in .env.local\n' > .env
  else
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)" > .env
  fi
  echo "Created ignored .env with local settings."
fi

env_files=(--env-file .env)
if [[ -z "${POSTGRES_PASSWORD:-}" ]] && ! grep -Eq '^[[:space:]]*POSTGRES_PASSWORD[[:space:]]*=[[:space:]]*[^[:space:]]+' .env; then
  if [[ ! -e .env.local ]]; then
    umask 077
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)" > .env.local
    echo "Existing .env has no password; created ignored .env.local without changing .env."
  fi
  env_files+=(--env-file .env.local)
fi

compose() { docker compose "${env_files[@]}" "$@"; }

if [[ "${1:-}" == --stop ]]; then
  compose down
  echo "PostgreSQL and Kafka stopped; data volumes retained."
  exit 0
fi

verify() {
  compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT 1"' | grep -qx 1
  echo "PostgreSQL ready (SELECT 1 succeeded)."
  compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic claims.notifications.v1
  echo "Kafka ready; notification topic present."
}

if [[ "${1:-}" == --verify ]]; then
  verify
  exit 0
fi

compose up -d --wait --wait-timeout 180 postgres kafka
compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic claims.notifications.v1 --partitions 1 --replication-factor 1
verify

if [[ "${1:-}" == --infra-only ]]; then
  exit 0
fi

# Until there is application code, starting the infrastructure completes the
# one-command setup. A Spring Boot Maven/Gradle wrapper will run in the
# foreground automatically once it is added.
if [[ -f pom.xml && -x ./mvnw ]]; then
  app_command=(./mvnw spring-boot:run)
elif [[ -f build.gradle && -x ./gradlew ]] || [[ -f build.gradle.kts && -x ./gradlew ]]; then
  app_command=(./gradlew bootRun)
else
  echo "No Java app yet; infrastructure is ready."
  exit 0
fi

compose_postgres_setting() {
  compose config --format json | python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["postgres"]["environment"][sys.argv[1]])' "$1"
}

postgres_host_port="$(compose config --format json | python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["postgres"]["ports"][0]["published"])')"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${postgres_host_port}/$(compose_postgres_setting POSTGRES_DB)"
export SPRING_DATASOURCE_USERNAME="$(compose_postgres_setting POSTGRES_USER)"
export SPRING_DATASOURCE_PASSWORD="$(compose_postgres_setting POSTGRES_PASSWORD)"
kafka_host_port="$(compose config --format json | python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["kafka"]["ports"][0]["published"])')"
export SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:${kafka_host_port}"
echo "Starting Spring Boot app against the ready local services..."
exec "${app_command[@]}"
