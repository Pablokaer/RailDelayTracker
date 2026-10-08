#!/usr/bin/env bash
# Tests for deploy/deploy.sh with fake systemctl and curl:  bash deploy/test_deploy.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FAILS=0

mkdir -p "$TMP/bin"
# systemctl just records the call; curl "fails" while current.jar holds the marker BAD.
printf '#!/bin/sh\necho "$@" >> "$APP_DIR/systemctl.log"\n' > "$TMP/bin/systemctl"
printf '#!/bin/sh\n! grep -q BAD "$APP_DIR/current.jar"\n' > "$TMP/bin/curl"
chmod +x "$TMP/bin/systemctl" "$TMP/bin/curl"

export PATH="$TMP/bin:$PATH" APP_DIR="$TMP/app" LOG_FILE="$TMP/deploy.log"
export HEALTH_RETRIES=2 HEALTH_INTERVAL=0 KEEP_RELEASES=2
SHA1=$(printf 'a%.0s' {1..40}); SHA2=$(printf 'b%.0s' {1..40}); SHA3=$(printf 'c%.0s' {1..40}); SHA4=$(printf 'd%.0s' {1..40})

jar() { printf 'PK%s' "$1"; }
run() { local sha="$1"; shift; jar "$@" | bash "$HERE/deploy.sh" "$sha" >/dev/null 2>&1; }
check() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 (expected '$3', got '$2')"; FAILS=$((FAILS + 1)); fi; }
live() { basename "$(readlink -f "$APP_DIR/current.jar")"; }

run "$SHA1" one; check "first deploy succeeds" "$?" 0
check "current.jar points at the release" "$(live)" "ierailmetrics-$SHA1.jar"
check "service was restarted" "$(grep -c restart "$APP_DIR/systemctl.log")" 1

run "$SHA2" BAD; check "unhealthy deploy fails" "$?" 1
check "rolled back to previous release" "$(live)" "ierailmetrics-$SHA1.jar"

run "$SHA3" three; check "deploy after a rollback succeeds" "$?" 0
run "$SHA4" four; check "next deploy succeeds" "$?" 0
check "old and failed releases are pruned (keeps the newest 2)" "$(ls "$APP_DIR"/releases/ierailmetrics-*.jar | wc -l)" 2
check "live release is never pruned" "$(live)" "ierailmetrics-$SHA4.jar"

printf 'not a jar' | bash "$HERE/deploy.sh" "$SHA1" >/dev/null 2>&1; check "rejects input that is not a jar" "$?" 3
jar x | bash "$HERE/deploy.sh" "not-a-sha" >/dev/null 2>&1; check "rejects a non-sha argument" "$?" 2
check "bad input leaves the live release alone" "$(live)" "ierailmetrics-$SHA4.jar"
jar five | SSH_ORIGINAL_COMMAND="$SHA3" bash "$HERE/deploy.sh" >/dev/null 2>&1; check "takes the sha from SSH_ORIGINAL_COMMAND" "$(live)" "ierailmetrics-$SHA3.jar"

exit $FAILS
