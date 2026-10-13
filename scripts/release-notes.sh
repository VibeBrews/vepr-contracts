#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  echo "Example: $0 0.1.0 or $0 v0.1.0" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHANGELOG="$REPO_ROOT/CHANGELOG.md"

if [ ! -f "$CHANGELOG" ]; then
  echo "❌ Error: CHANGELOG.md not found at $CHANGELOG" >&2
  exit 1
fi

RAW_VERSION="$1"
VERSION="${RAW_VERSION#v}"

awk -v ver="$VERSION" '
BEGIN {
    found = 0;
    valid = 0;
    in_section = 0;
}
$0 ~ "^## \\[" ver "\\]" {
    found = 1;
    if ($0 ~ "^## \\[" ver "\\] - [0-9]{4}-[0-9]{2}-[0-9]{2}") {
        valid = 1;
        in_section = 1;
        print;
        next;
    } else {
        print "❌ Error: Section for [" ver "] in CHANGELOG.md is undated or marked Unreleased." > "/dev/stderr";
        exit 1;
    }
}
in_section {
    if ($0 ~ "^## \\[") {
        exit 0;
    }
    print;
}
END {
    if (found == 0) {
        print "❌ Error: Version [" ver "] not found in CHANGELOG.md." > "/dev/stderr";
        exit 1;
    }
    if (valid == 0) {
        exit 1;
    }
}
' "$CHANGELOG"
