#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v docker >/dev/null || { echo 'Docker is required' >&2; exit 1; }
docker compose version >/dev/null

# Generate secrets inside Docker, without host Java/Python/OpenSSL or reading existing env files.
if [[ "${1:-}" != --verify && "${1:-}" != --stop ]]; then
  docker run --rm --user "$EUID:${GROUPS[0]}" --entrypoint sh -v "$PWD:/workspace" postgres:17-alpine -c '
    umask 077
    if [ -L /workspace/.local ]; then exit 1; fi
    mkdir -p /workspace/.local
    chmod 700 /workspace/.local
    for name in claimant-a claimant-b officer-o1 officer-o2; do
      if [ -L "/workspace/.local/$name.password" ]; then exit 1; fi
      if [ ! -e "/workspace/.local/$name.password" ]; then
        tr -dc A-Za-z0-9 </dev/urandom | head -c 40 >"/workspace/.local/$name.password" || true
      fi
      chmod 600 "/workspace/.local/$name.password"
    done
    if [ ! -e /workspace/.env ] && [ ! -e /workspace/.env.local ]; then
      { printf "POSTGRES_PASSWORD="; tr -dc A-Za-z0-9 </dev/urandom | head -c 40 || true; printf "\n"; } >/workspace/.env
      chmod 600 /workspace/.env
    fi
  '
fi
env_files=()
[[ ! -e .env ]] || env_files+=(--env-file .env)
[[ ! -e .env.local ]] || env_files+=(--env-file .env.local)
compose() { docker compose "${env_files[@]}" "$@"; }
case "${1:-}" in
  --stop) compose down; exit ;;
  --verify)
    compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT 1"' | grep -qx 1
    echo 'PostgreSQL ready'
    compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic claims.notifications.v1 >/dev/null
    echo 'Kafka ready; notification topic present'
    compose exec -T app sh -c 'wget -q -O /dev/null http://localhost:8080/api/csrf'
    echo 'Application ready'
    exit ;;
  "") ;;
  *) echo 'Usage: ./start.sh [--verify|--stop]' >&2; exit 2 ;;
esac
compose up -d --wait --wait-timeout 180 postgres kafka
compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic claims.notifications.v1 --partitions 1 --replication-factor 1 >/dev/null
compose up -d --build --wait --wait-timeout 180 app
echo "PostgreSQL ready; Kafka notification topic present; application ready at http://127.0.0.1:${CLAIMS_APP_PORT:-8080}"
