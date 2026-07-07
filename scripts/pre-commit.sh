#!/usr/bin/env bash
# Fast pre-commit checks: secret scan on staged files.
# Full build/tests run via scripts/verify.sh before each commit batch.
set -euo pipefail

staged=$(git diff --cached --name-only --diff-filter=ACM | grep -v '^scripts/pre-commit.sh$' || true)
[ -z "$staged" ] && exit 0

if echo "$staged" | xargs -r grep -lEI \
    -e 'BEGIN (RSA|EC|OPENSSH|DSA) PRIVATE KEY' \
    -e 'PVEAPIToken=' \
    -e 'ghp_[A-Za-z0-9]{36}' \
    -e 'AKIA[0-9A-Z]{16}' 2>/dev/null; then
  echo "pre-commit: possible secret detected in staged files (above). Aborting." >&2
  exit 1
fi
