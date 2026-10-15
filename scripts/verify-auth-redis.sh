#!/usr/bin/env bash
# Uses current compiled probe and packaged runtime; never starts/stops or selects a shared Redis.
set -euo pipefail
PROBE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROBE_ROOT"
[[ "${ROBOT_VERIFY_REDIS_DISPOSABLE:-}" == 1 && "${ROBOT_VERIFY_REDIS_HOST:-}" == 127.0.0.1 ]] || { printf 'explicit loopback disposable Redis required\n' >&2; exit 2; }
[[ "${ROBOT_VERIFY_REDIS_PORT:-}" =~ ^[0-9]+$ && "${ROBOT_VERIFY_REDIS_NAMESPACE:-}" =~ ^verify-[a-f0-9]{12}$ ]] || { printf 'port and unique verify namespace required\n' >&2; exit 2; }
PROBE_PHASE="${1:-full}"
[[ "$PROBE_PHASE" == full || "$PROBE_PHASE" == prepare || "$PROBE_PHASE" == verify ]] || { printf 'phase must be full/prepare/verify\n' >&2; exit 2; }
PROBE_LIBS="$(mktemp -d "${TMPDIR:-/tmp}/robot-auth-redis-libs.XXXXXX")"
trap 'rm -rf "$PROBE_LIBS"' EXIT
export ROBOT_VERIFY_REDIS_LIB_DIR="$PROBE_LIBS"
python3 - <<'PY'
import os, pathlib, zipfile
jar = pathlib.Path('robot-services/robot-auth/target/robot-auth-1.0.0-SNAPSHOT.jar')
probe = pathlib.Path('robot-services/robot-auth/target/test-classes/com/robot/auth/authn/service/AuthRedisOutboxProbe.class')
if not jar.exists() or not probe.exists():
    raise SystemExit('run the unified Maven verify first; current packaged runtime and compiled probe are required')
destination = pathlib.Path(os.environ['ROBOT_VERIFY_REDIS_LIB_DIR'])
destination.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(jar) as archive:
    for item in archive.infolist():
        if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
            (destination / pathlib.PurePosixPath(item.filename).name).write_bytes(archive.read(item))
PY
PROBE_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$PROBE_JAVA" -cp "$PROBE_ROOT/robot-services/robot-auth/target/test-classes:$PROBE_ROOT/robot-services/robot-auth/target/classes:$PROBE_LIBS/*" com.robot.auth.authn.service.AuthRedisOutboxProbe "$PROBE_PHASE"
