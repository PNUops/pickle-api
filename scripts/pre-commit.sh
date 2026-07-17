#!/usr/bin/env bash
# Fast pre-commit checks: secret scan on staged files.
# Full build/tests run via scripts/verify.sh before each commit batch.
set -euo pipefail

staged=$(git diff --cached --name-only --diff-filter=ACM | grep -v '^scripts/pre-commit.sh$' || true)
[ -z "$staged" ] && exit 0

# PVEAPIToken: the literal header name is legitimate source since the M3
# Proxmox client (header assembly, log masking, tests) — only flag values
# shaped like a real token: <user@realm>!<name>=<uuid secret>.
# The PEM armor marker is anchored to line-start (after optional indentation),
# the shape of a real key file or pasted key. Code that *emits* OpenSSH PEMs
# (the M5.5 SSH key generator) carries the marker inside a quoted string —
# preceded by `return "` etc. — so the anchor skips it without weakening
# detection of an actually-staged private key.
if echo "$staged" | xargs -r grep -lEI \
    -e '^[[:space:]]*-----BEGIN (RSA|EC|OPENSSH|DSA) PRIVATE KEY' \
    -e 'PVEAPIToken=[^ =]+![^ =]+=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' \
    -e 'ghp_[A-Za-z0-9]{36}' \
    -e 'AKIA[0-9A-Z]{16}' 2>/dev/null; then
  echo "pre-commit: possible secret detected in staged files (above). Aborting." >&2
  exit 1
fi
