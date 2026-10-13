#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Find candidate tags merged into HEAD, excluding any tags that point at HEAD.
# This gives the previous release on a release tag run, and the latest release on PR or main runs.
HEAD_TAGS="$(git tag --points-at HEAD 2>/dev/null || true)"

BASE=""
while IFS= read -r tag; do
  [ -z "$tag" ] && continue
  if echo "$HEAD_TAGS" | grep -qx "$tag"; then
    continue
  fi
  BASE="$tag"
  break
done < <(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname)

if [ -z "$BASE" ]; then
  echo "ℹ️  No base tag found to check breaking changes against. Skipping breaking-change check."
  exit 0
fi

echo "Checking for breaking changes against base tag: $BASE"

if ! command -v oasdiff >/dev/null 2>&1; then
  echo "❌ Error: oasdiff CLI is not installed or not in PATH." >&2
  exit 1
fi

if ! command -v buf >/dev/null 2>&1; then
  echo "❌ Error: buf CLI is not installed or not in PATH." >&2
  exit 1
fi

# 1. OpenAPI breaking checks
# Extract the base openapi tree to a temporary directory so relative $refs (e.g. ./components/common.yaml)
# resolve correctly without breaking on /dev/fd paths from process substitution.
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

git archive "$BASE" openapi | tar -x -C "$TMP_DIR"

for spec in openapi/partner.yaml openapi/device.yaml; do
  if [ -f "$TMP_DIR/$spec" ]; then
    echo "→ Checking $spec against $BASE..."
    oasdiff breaking --fail-on ERR "$TMP_DIR/$spec" "$spec"
  else
    echo "→ Skipping $spec (not present in $BASE)"
  fi
done

# 2. Protobuf breaking checks
echo "→ Checking protobuf contracts against $BASE..."
buf breaking --against ".git#tag=$BASE"

echo "✅ No breaking changes detected against $BASE."
