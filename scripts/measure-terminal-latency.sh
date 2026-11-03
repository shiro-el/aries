#!/usr/bin/env bash
# Measures key -> drawn echo on a connected Android device or emulator
# (TerminalLatencyTest): starts the fixture sshd with the pinned herdr-core
# on this machine, runs the instrumented test against it, prints the result
# line and removes the container.
#
# Usage: scripts/measure-terminal-latency.sh [KEYS]   (default 20 keystrokes)
#   ANDROID_SERIAL      device to use (adb's default otherwise)
#   ARIES_LATENCY_HOST  address the device reaches this machine at
#                       (default 10.0.2.2, the emulator's alias for the host;
#                       a real phone needs this machine's LAN/Tailscale address,
#                       and the port is then published on 0.0.0.0)
#   ARIES_TEST_CONTAINER_PREFIX  container name prefix (default aries-test)
set -euo pipefail

keys="${1:-20}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bin="$("$root/scripts/fetch-herdr-core.sh")"
host="${ARIES_LATENCY_HOST:-10.0.2.2}"
bind=127.0.0.1
[[ "$host" == 10.0.2.2 ]] || bind=0.0.0.0
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
name="${ARIES_TEST_CONTAINER_PREFIX:-aries-test}-latency-$$"
image="${ARIES_TEST_CONTAINER_PREFIX:-aries-test}-latency-sshd"

docker build -q -t "$image" "$root/fixtures/sshd" >/dev/null
docker run -d --rm --name "$name" -p "$bind::22" -v "$bin:/usr/local/bin/herdr-core:ro" "$image" >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
port="$(docker port "$name" 22/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 50); do
  docker exec "$name" sh -c 'nc -z 127.0.0.1 22' 2>/dev/null && break
  sleep 0.2
done

"$adb" logcat -c
(cd "$root/android" && ./gradlew -q :app:connectedDebugAndroidTest \
  ${ARIES_ABIS:+-Paries.abis=$ARIES_ABIS} \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.shiroel.aries.TerminalLatencyTest \
  -Pandroid.testInstrumentationRunnerArguments.latencyPort="$port" \
  -Pandroid.testInstrumentationRunnerArguments.latencyHost="$host" \
  -Pandroid.testInstrumentationRunnerArguments.latencyKeys="$keys")
"$adb" logcat -d -s AriesLatency:I | grep 'latency over'
