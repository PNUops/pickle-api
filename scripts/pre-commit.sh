#!/usr/bin/env bash
# Fast pre-commit secret scan. Scans the STAGED BLOB CONTENT via `git show :path`
# (the index object), NOT the working tree — so a secret that was staged and then
# edited/reverted in the working copy is still caught, and an unstaged
# working-tree secret does not block an unrelated commit. Full build/tests run
# via scripts/verify.sh before each commit batch.
set -euo pipefail

# Regexes tuned for near-zero false positives. A bare "PVEAPIToken=" placeholder
# and angle-bracket placeholders like <relay-wg-private-key> never match — only
# real secret shapes do.
# Keep this list identical to the one the verification gate applies. They were
# allowed to differ once, and the weaker of the two was the one every commit
# made on a developer's machine passed through; a shape added here and not there
# now fails a verification run.
patterns=(
  # Any PEM private key, whatever the type word and however it is spaced. The
  # earlier form named four types and one space, so a PKCS#8 key (BEGIN PRIVATE
  # KEY) and a key written with two spaces both went through.
  -e 'BEGIN[[:space:]]+([A-Z0-9]+[[:space:]]+)*PRIVATE[[:space:]]+KEY'
  # Proxmox API token WITH a real uuid secret (bare literal placeholder allowed).
  -e 'PVEAPIToken=[^ =]+![^ =]+=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
  -e 'gh[pousr]_[A-Za-z0-9]{36}'
  -e 'github_pat_[A-Za-z0-9_]{60,}'
  -e 'AKIA[0-9A-Z]{16}'
  -e 'xox[baprs]-[A-Za-z0-9-]{10,}'
  -e 'AIza[0-9A-Za-z_-]{35}'
  # WireGuard / preshared key: a 32-byte value is 43 base64 chars + '=' on a
  # PrivateKey/PresharedKey line (covers wg0.conf secrets; placeholders excluded).
  -e '(PrivateKey|PresharedKey)[[:space:]]*=[[:space:]]*[A-Za-z0-9+/]{43}='
  # A 64-hex value on a line that says what it is.
  -e '(token|secret|password|passwd|apikey|api_key)["'"'"':= ]{1,8}[0-9a-f]{64}'
)

mapfile -t files < <(git diff --cached --name-only --diff-filter=ACM)
blob=$(mktemp)
trap 'rm -f "$blob"' EXIT
hit=0
for path in "${files[@]}"; do
  [ -n "$path" ] || continue
  # Skip the hook script itself: its pattern definitions must not self-trip.
  case "$path" in scripts/pre-commit.sh) continue ;; esac
  # Read the index object into a file first. Piping `git show` straight into
  # grep threw away git's exit status, so a blob that could not be read produced
  # grep's "no match" and the file passed as clean without ever being scanned.
  # An unreadable staged object is an unscanned file, so it blocks the commit.
  if ! git show ":$path" >"$blob" 2>/dev/null; then
    echo "pre-commit: cannot read staged content of: $path (not scanned)" >&2
    hit=1
    continue
  fi
  # A line that legitimately shows a secret-shaped string says so on itself, and
  # the scan believes it — the same allowance the documentation gate honours. It
  # was missing here, so a line the gate passed could still be unable to reach a
  # commit, and the only way through was --no-verify, which switches off every
  # other shape as well. Match on the line, never on the file.
  if grep -EIn "${patterns[@]}" "$blob" | grep -qvF '# not-a-secret'; then
    echo "pre-commit: possible secret in staged content of: $path" >&2
    hit=1
  fi
done

# A repository can declare that this scan is advisory for it:
#
#     git config pickle.secretscan warn
#
# It is set per checkout, never here, because the repositories that must keep the
# hard failure are the published ones and this file is installed into all of them.
# The one that sets it is the private documentation repository, which holds real
# credentials deliberately; blocking there would be answered with --no-verify on
# every commit, and a check routinely bypassed protects nothing. Warning keeps the
# finding visible without training anyone to skip the hook.
if [ "$hit" = 1 ] && [ "$(git config --get pickle.secretscan 2>/dev/null)" = warn ]; then
  echo "pre-commit: secret-shaped content above is advisory in this repository (pickle.secretscan=warn)" >&2
  hit=0
fi

if [ "$hit" = 1 ]; then
  echo "pre-commit: aborting (secret pattern in staged content, or content that could not be scanned). Use --no-verify only for a confirmed false positive." >&2
  exit 1
fi

# A repo whose verification is cheap enough to run per commit opts in by
# shipping an executable scripts/hook-verify.sh; the hook runs it and refuses
# the commit when it fails. Repos needing a build or a test suite do not opt in,
# because a hook nobody can afford to wait for is a hook everybody bypasses.
# Keep what it runs self-contained: a check that reads sibling repositories ties
# this commit to another working tree, which parallel sessions make unstable.
if [ -x scripts/hook-verify.sh ]; then
  scripts/hook-verify.sh || {
    echo "pre-commit: aborting (scripts/hook-verify.sh failed)." >&2
    exit 1
  }
fi
exit 0
