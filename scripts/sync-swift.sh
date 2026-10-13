#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CHECK_ONLY=false
if [ "${1:-}" = "--check" ]; then
  CHECK_ONLY=true
fi

PAIRS=(
  "openapi/device.yaml:Sources/VeprDeviceAPI/openapi.yaml:Swift device spec"
  "signing/vectors/v1.json:Tests/VeprSigningTests/Resources/v1.json:Swift signing test vectors"
)

EXIT_CODE=0

for item in "${PAIRS[@]}"; do
  IFS=":" read -r SRC_REL DST_REL LABEL <<< "$item"
  SRC="$REPO_ROOT/$SRC_REL"
  DST="$REPO_ROOT/$DST_REL"

  if [ "$CHECK_ONLY" = true ]; then
    if [ ! -f "$DST" ]; then
      echo "❌ $DST does not exist. Run scripts/sync-swift.sh to sync."
      EXIT_CODE=1
    elif ! cmp -s "$SRC" "$DST"; then
      echo "❌ $SRC and $DST differ. Run scripts/sync-swift.sh to sync."
      diff -u "$SRC" "$DST" || true
      EXIT_CODE=1
    else
      echo "✅ $LABEL is in sync."
    fi
  else
    mkdir -p "$(dirname "$DST")"
    cp "$SRC" "$DST"
    echo "✅ Synced $SRC -> $DST"
  fi
done

exit $EXIT_CODE
