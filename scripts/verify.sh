#!/usr/bin/env bash
# Same entry used locally and by GitHub Actions. No existing database can be selected.
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$TASK_ROOT"
VERIFY_MYSQL=false
case "${1:-}" in
  "") ;;
  --mysql-upgrade) VERIFY_MYSQL=true ;;
  *) printf 'usage: scripts/verify.sh [--mysql-upgrade]\n' >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then printf 'unexpected arguments\n' >&2; exit 2; fi
if "$VERIFY_MYSQL"; then
  python3 - <<'PY'
import os
host=os.getenv('ROBOT_VERIFY_MYSQL_HOST','127.0.0.1')
if host not in ('127.0.0.1','localhost','::1'):
    raise SystemExit('upgrade tests only accept a loopback MySQL host')
port=int(os.getenv('ROBOT_VERIFY_MYSQL_PORT','3306'))
if not 1 <= port <= 65535 or not os.getenv('ROBOT_VERIFY_MYSQL_USER'):
    raise SystemExit('valid port and ROBOT_VERIFY_MYSQL_USER required')
PY
fi
python3 scripts/migration_manifest.py
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts/runtime -p 'test_*.py'
VERIFY_MAVEN="${MAVEN_CMD:-./mvnw}"
VERIFY_ARGS=(-B -ntp clean verify -Probot-quality)
if "$VERIFY_MYSQL"; then VERIFY_ARGS+=(-Pmysql-upgrade); fi
VERIFY_LOG="$(mktemp "${TMPDIR:-/tmp}/robot-maven-verify.XXXXXX")"
trap 'rm -f "$VERIFY_LOG"' EXIT
set +e
"$VERIFY_MAVEN" "${VERIFY_ARGS[@]}" 2>&1 | tee "$VERIFY_LOG"
VERIFY_PIPE_RESULT=("${PIPESTATUS[@]}")
set -e
mkdir -p target/verification
cp "$VERIFY_LOG" target/verification/maven-verify.log
if [[ "${VERIFY_PIPE_RESULT[0]}" -ne 0 ]]; then exit "${VERIFY_PIPE_RESULT[0]}"; fi
if [[ "${VERIFY_PIPE_RESULT[1]}" -ne 0 ]]; then exit "${VERIFY_PIPE_RESULT[1]}"; fi
python3 scripts/check_java_quality.py --root . --maven-log target/verification/maven-verify.log > target/verification/java-quality.json
python3 scripts/check_supply_chain.py --sbom target/bom.json --policy quality/supply-chain-policy.json --output target/verification/supply-chain.json
python3 scripts/verify_packaged_mappers.py
python3 scripts/verification_summary.py
