#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOWS_DIR="$REPO_ROOT/.github/workflows"

if [ ! -d "$WORKFLOWS_DIR" ]; then
  echo "❌ Error: Workflows directory $WORKFLOWS_DIR does not exist" >&2
  exit 1
fi

echo "🔍 Verifying pinned action commit SHAs in $WORKFLOWS_DIR against git ls-remote..."

FAILED=0

# Find all lines matching 'uses: <action>@<sha> # <tag>'
while IFS= read -r line; do
  [ -z "$line" ] && continue
  
  # Parse out action, sha, and tag
  # Format: uses: owner/repo[/subpath]@40-hex-sha # vX.Y.Z
  ACTION_FULL=$(echo "$line" | sed -E 's/.*uses:[[:space:]]*([^@]+)@([0-9a-fA-F]{40})[[:space:]]*#[[:space:]]*(v[0-9].*)/\1/')
  PINNED_SHA=$(echo "$line" | sed -E 's/.*uses:[[:space:]]*([^@]+)@([0-9a-fA-F]{40})[[:space:]]*#[[:space:]]*(v[0-9].*)/\2/')
  TAG=$(echo "$line" | sed -E 's/.*uses:[[:space:]]*([^@]+)@([0-9a-fA-F]{40})[[:space:]]*#[[:space:]]*(v[0-9].*)/\3/')

  # Repo is owner/name (e.g. actions/checkout, gradle/actions, bufbuild/buf-action)
  REPO_OWNER=$(echo "$ACTION_FULL" | cut -d'/' -f1)
  REPO_NAME=$(echo "$ACTION_FULL" | cut -d'/' -f2)
  REPO="${REPO_OWNER}/${REPO_NAME}"

  echo "Checking ${ACTION_FULL} @ ${TAG} (pinned: ${PINNED_SHA})..."
  
  # Query remote tags including dereferenced annotated tag ^{}
  LS_REMOTE=$(git ls-remote --tags "https://github.com/${REPO}.git" "$TAG" "${TAG}^{}")

  PEELED_SHA=$(echo "$LS_REMOTE" | grep -F "refs/tags/${TAG}^{}" | awk '{print $1}' || true)
  DIRECT_SHA=$(echo "$LS_REMOTE" | grep -F "refs/tags/${TAG}" | grep -v '\^{}' | awk '{print $1}' || true)

  REMOTE_SHA="${PEELED_SHA:-$DIRECT_SHA}"

  if [ -z "$REMOTE_SHA" ]; then
    echo "❌ Tag $TAG not found for $REPO" >&2
    FAILED=1
    continue
  fi

  if [ "$REMOTE_SHA" != "$PINNED_SHA" ]; then
    echo "❌ Mismatch for $REPO @ $TAG! Expected $PINNED_SHA, remote has $REMOTE_SHA" >&2
    FAILED=1
  else
    echo "  ✅ SHA matches: $REMOTE_SHA"
  fi
done < <(grep -hoE 'uses:[[:space:]]*[^@]+@[0-9a-fA-F]{40}[[:space:]]*#[[:space:]]*v[0-9][^[:space:]]*' "$WORKFLOWS_DIR"/*.yml | sort -u)

if [ "$FAILED" -ne 0 ]; then
  echo "❌ Pinned action verification failed." >&2
  exit 1
fi

echo "✅ All pinned action SHAs verified successfully against remote tags."
