#!/usr/bin/env bash
# Code outside the Agent adapters must not branch on Agent names or id shapes.
set -euo pipefail
cd "$(dirname "$0")/.."
consumers=(crates/beacon/src/chat/answer.rs crates/beacon/src/chat/follow.rs crates/beacon/src/status_push.rs)
status=0
if rg -n 'agent\.name\(\)\s*[!=]=|agent\s*==\s*"|starts_with\("claude-hook:"\)|permission::PREFIX' "${consumers[@]}"; then
  echo "agent boundary: consumers branch on Agent names or request id shapes" >&2
  status=1
fi
if rg -n 'permission::detect|claude_hook::(pending|open)\b' "${consumers[@]}"; then
  echo "agent boundary: consumers find pending requests themselves" >&2
  status=1
fi
exit $status
