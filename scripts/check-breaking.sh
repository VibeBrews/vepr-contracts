#!/usr/bin/env bash
set -euo pipefail

LATEST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

if [ -z "$LATEST_TAG" ]; then
  echo "ℹ️  No git tags found. Skipping breaking-change check."
  exit 0
fi

echo "Checking for breaking changes against tag: $LATEST_TAG"

if ! command -v oasdiff >/dev/null 2>&1; then
  echo "❌ Error: oasdiff CLI is not installed or not in PATH."
  exit 1
fi

for spec in openapi/partner.yaml openapi/device.yaml; do
  if git show "${LATEST_TAG}:${spec}" >/dev/null 2>&1; then
    echo "→ Checking $spec against $LATEST_TAG..."
    oasdiff breaking <(git show "${LATEST_TAG}:${spec}") "$spec"
  else
    echo "→ Skipping $spec (not present in $LATEST_TAG)"
  fi
done

echo "✅ No breaking changes detected."
