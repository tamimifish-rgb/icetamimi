#!/usr/bin/env bash
# Tests one backend script on a throwaway Postgres 16:
#   backend/test/run.sh backend/92-closing-handover.sql
# Loads stub-schema.sql, applies the script, then runs backend/test/<same name>.test.sql
# (and, when it exists, applies <name>-rollback.sql and runs <name>.rollback-test.sql).
# Prints every "ok" line and exits non-zero on the first failure.
set -euo pipefail
script=$(realpath "$1")
here=$(cd "$(dirname "$0")" && pwd)
name=$(basename "$script" .sql)
test="$here/$name.test.sql"
[ -f "$test" ] || { echo "missing $test" >&2; exit 2; }
bin=$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)
dir=/var/lib/postgresql/pureice-backend-test-$$
port=$((55000 + $$ % 1000))
cleanup() { su postgres -c "$bin/pg_ctl -D $dir/data stop -m immediate" >/dev/null 2>&1 || true; rm -rf "$dir"; }
trap cleanup EXIT
su postgres -c "mkdir -p $dir && $bin/initdb -D $dir/data -A trust -U postgres >/dev/null && $bin/pg_ctl -D $dir/data -o '-p $port -k $dir -c listen_addresses=' -l $dir/log start -w >/dev/null"
cp "$here/stub-schema.sql" "$script" "$test" "$dir/" && chown postgres "$dir"/*.sql
psql() { su postgres -c "psql -h $dir -p $port -U postgres -X -q -v ON_ERROR_STOP=1 $*"; }
psql "-c 'create database t'"
psql "-d t -f $dir/stub-schema.sql" >/dev/null
psql "-d t -f $dir/$(basename "$script")" >/dev/null
psql "-d t -f $dir/$(basename "$test")" 2>&1 | sed -n 's/.*NOTICE:  //p; /ERROR/p'
echo "PASS: $name"
