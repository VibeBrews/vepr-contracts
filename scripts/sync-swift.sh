#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CHECK_ONLY=false
if [ "${1:-}" = "--check" ]; then
  CHECK_ONLY=true
fi

DEVICE_SPEC="$REPO_ROOT/openapi/device.yaml"
SWIFT_SPEC="$REPO_ROOT/Sources/VeprDeviceAPI/openapi.yaml"

if [ "$CHECK_ONLY" = true ]; then
  if [ ! -f "$SWIFT_SPEC" ]; then
    echo "❌ $SWIFT_SPEC does not exist. Run scripts/sync-swift.sh to sync."
    exit 1
  fi
  if ! cmp -s "$DEVICE_SPEC" "$SWIFT_SPEC"; then
    echo "❌ $DEVICE_SPEC and $SWIFT_SPEC differ. Run scripts/sync-swift.sh to sync."
    diff -u "$DEVICE_SPEC" "$SWIFT_SPEC" || true
    exit 1
  fi
  echo "✅ Swift device spec is in sync."
else
  mkdir -p "$(dirname "$SWIFT_SPEC")"
  cp "$DEVICE_SPEC" "$SWIFT_SPEC"
  echo "✅ Synced $DEVICE_SPEC -> $SWIFT_SPEC"
fi
