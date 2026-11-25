#!/usr/bin/env bash
# Applies the XamPulse ClickHouse DDL to the LOCAL dev container, for people who want the tables without starting the
# backend. The backend's ClickHouseMigrationRunner (xampulse.enabled=true) applies the same files and is the normal path;
# every statement is IF NOT EXISTS so running both is safe.
set -euo pipefail
cd "$(dirname "$0")/.."
SCHEMA_DIR="../../xamops-service/src/main/resources/xampulse/clickhouse"
[ -f .env ] && set -a && . ./.env && set +a
: "${XP_CH_WRITER_PASSWORD:?copy .env.example to .env first}"
for f in "$SCHEMA_DIR"/V*.sql; do
  echo "applying $(basename "$f")"
  # Single node: the {{REPLICATED}} placeholder becomes empty (same as xampulse.clickhouse.replicated=false).
  sed 's/{{REPLICATED}}//g' "$f" | docker compose exec -T clickhouse \
    clickhouse-client --user xp_writer --password "$XP_CH_WRITER_PASSWORD" --database xampulse --multiquery
done
# Reader: an SQL user whose ONLY role is xp_reader_role (SELECT on xp_* tables + tenant row policies + bounded profile).
: "${XP_CH_READER_PASSWORD:?copy .env.example to .env first}"
case "$XP_CH_READER_PASSWORD" in *[\'\\]*) echo "XP_CH_READER_PASSWORD must not contain quotes or backslashes" >&2; exit 1;; esac
docker compose exec -T clickhouse clickhouse-client --user xp_writer --password "$XP_CH_WRITER_PASSWORD" --database xampulse --multiquery \
  --query "CREATE USER IF NOT EXISTS xp_reader IDENTIFIED WITH sha256_password BY '$XP_CH_READER_PASSWORD' DEFAULT DATABASE xampulse;
           GRANT xp_reader_role TO xp_reader;
           SET DEFAULT ROLE xp_reader_role TO xp_reader"
echo "schema applied"
