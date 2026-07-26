#!/usr/bin/env bash
# Publication-hygiene gate. Sourced by scripts/verify.sh, so a violation fails
# before it can be committed.
#
# Why it exists: this repo is published. The rules below were enforced by hand
# twice (2026-07-24, 2026-07-26) and both passes missed real violations —
# including a whole class, because a word-boundary pattern never matched a
# letter suffix like M4A. A grep that runs on every verify is the only version
# of this that stays true.
#
# The script is duplicated per repo on purpose: a shared copy would have to live
# somewhere central, and pointing at it from a published repo is itself the kind
# of cross-reference these rules forbid. Keep the five copies identical.
#
# Usage: hygiene_check public   # this repo is published
#        hygiene_check infra    # private-but-shared: may name vault paths

# Documentation-repo filenames. A bare mention is a reference too — that is how
# the first scrub missed most of its targets. `network.md` is deliberately absent:
# the infra repo has a runbook of the same name and the collision would make the
# gate unusable there.
HYGIENE_DOC_NAMES='internal\.md|permission-matrix\.md|hosts\.md|credentials\.md|domains-tls\.md|scheduled-jobs\.md|product-spec\.md|status\.md|architecture\.md|roadmap\.md|glossary\.md|commit-convention\.md|dev-setup\.md|production-gates\.md|backlog\.md|console-views\.md|ssh-access\.md|out-of-scope\.md|findings-triage\.md|data-model\.md|network-ipam\.md|ports\.md|security\.md|provisioning\.md|environments-deploy\.md|verification-gaps\.md'

# The private repo and the secret vault, by path and by the names of the scripts
# that live there.
HYGIENE_PRIVATE='(\binfra/|/root/pickle/secrets|(^|[^a-z])secrets/|deploy-(api|console|sshgw|proxy-agent)\.sh|create-(app|sshgw)-lxc\.sh|apply-(log-retention|tls-ciphers|terminal-ingress)\.sh|smoke-[a-z-]+\.sh|sync-systemd-units\.sh|cron-wrap\.sh|health-check\.sh)'

# Internal process vocabulary. The trailing [A-Z]? is load-bearing: M4A, W2-B.
HYGIENE_TOKENS='\b(M[0-9]+(\.[0-9]+)?[A-Z]?|W[0-9]+(\.[0-9]+)?(-[A-Z])?|G[0-9]|B[0-9]|A[0-9]|C[0-9]|R[12]|S([1-9]|1[0-3])|O([1-9]|10)|F[0-9]|H1|WP-[A-Z0-9]+|api-[A-Z]|Lane [A-Z])\b|보안 게이트|review finding|gate finding|work package'

# Substrings that merely LOOK like a token or a reference. They are REMOVED from
# the line before the test, never used to suppress the line: a line-level
# exclusion exempts everything else on that line, which is how an earlier
# revision let `M4A … contract v0.14.1` through, and how any comment sharing a
# line with an inline SVG passed. `d="…"` is stripped as a whole attribute so the
# next path command (M12…) cannot survive as a lookalike.
# The `$R1`/`$R2` entries are shell variable names appearing literally in a
# regex, not expansions. `S3` and `-O2` are allowed because the storage service
# and the compiler flag are likelier in real code than the finding IDs they
# collide with.
# shellcheck disable=SC2016
HYGIENE_ALLOW='d="[^"]*"|sha512-[A-Za-z0-9+/=]*|"integrity"|<path|\bD-?(1|7|14|30)\b|V[0-9]+__|contract v[0-9.]+|v[0-9]+\.[0-9]+\.[0-9]+|[0-9]{1,3}(\.[0-9]{1,3}){3}|\bR3F\b|\bT0\b|-m[0-9]{3}|grep -m[0-9]|-w[0-9]\b|-O[0-9]\b|\bS3\b|\bL4\b|PROXY v[0-9]|\bR[12]=|\$R[12]\b|"\$R[12]"'

# Files to scan: everything tracked except this script (it necessarily contains
# every pattern it searches for) and lockfiles (generated hashes trip the token
# pattern). Anchored on the script's own location so the caller's cwd is
# irrelevant.
hygiene_files() {
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" || return 1
  git -C "$root" ls-files -z | grep -zvE '(^|/)(hygiene\.sh|package-lock\.json|go\.sum)$'
}

# Strip the lookalikes, then keep what still matches.
hygiene_match() {
  sed -E "s@${HYGIENE_ALLOW}@@g" | grep -E "$1"
}

hygiene_check() {
  local kind="$1" rc=0 hits paths
  local -a files

  # Fail closed: an empty file list means the scan did not run (not a git
  # worktree, git missing), which must never read as "clean".
  mapfile -d '' -t files < <(hygiene_files) || true
  if [ "${#files[@]}" -eq 0 ]; then
    echo "hygiene: no files to scan — is this a git worktree?" >&2
    return 1
  fi

  hits=$(printf '%s\0' "${files[@]}" \
    | xargs -0 grep -HnIE "(\.\./docs|(^|[^a-z])docs/|${HYGIENE_DOC_NAMES})" 2>/dev/null \
    | hygiene_match "(\.\./docs|(^|[^a-z])docs/|${HYGIENE_DOC_NAMES})" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: reference to the documentation repository:" >&2
    echo "$hits" >&2
    rc=1
  fi

  if [ "$kind" = public ]; then
    hits=$(printf '%s\0' "${files[@]}" \
      | xargs -0 grep -HnIE "$HYGIENE_PRIVATE" 2>/dev/null \
      | hygiene_match "$HYGIENE_PRIVATE" || true)
    if [ -n "$hits" ]; then
      echo "hygiene: reference to the private infrastructure repository or the secret vault:" >&2
      echo "$hits" >&2
      rc=1
    fi
  fi

  hits=$(printf '%s\0' "${files[@]}" \
    | xargs -0 grep -HnIE "$HYGIENE_TOKENS" 2>/dev/null \
    | hygiene_match "$HYGIENE_TOKENS" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: internal process token (state the fact instead):" >&2
    echo "$hits" >&2
    rc=1
  fi

  # Path names carry the same rules: a file called hosts.md, or a directory
  # M7-notes/, says as much as a comment would.
  paths=$(printf '%s\n' "${files[@]}" \
    | hygiene_match "(${HYGIENE_DOC_NAMES}|${HYGIENE_TOKENS})" || true)
  if [ -n "$paths" ]; then
    echo "hygiene: file or directory name carries a documentation name or a process token:" >&2
    echo "$paths" >&2
    rc=1
  fi

  [ "$rc" -eq 0 ] && echo "hygiene OK"
  return "$rc"
}

# Proves the gate still DETECTS, end to end. It builds a throwaway git repo,
# commits one known violation at a time and asserts hygiene_check fails on each —
# so it exercises file enumeration, the greps and the exclusion logic, not just
# the pattern constants. An earlier version tested the patterns inline and so
# stayed green while the plumbing was sabotaged.
# shellcheck disable=SC2030,SC2031  # the checks run in subshells by design; the
# variables they read are assigned here and never written back.
hygiene_selftest() {
  local tmp rc=0 self line
  self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/hygiene.sh"
  tmp="$(mktemp -d)" || return 1
  mkdir -p "$tmp/scripts"
  cp "$self" "$tmp/scripts/hygiene.sh"
  git -C "$tmp" init -q
  git -C "$tmp" config user.email hygiene@example.invalid
  git -C "$tmp" config user.name hygiene

  while IFS= read -r line; do
    printf '%s\n' "$line" > "$tmp/sample.txt"
    git -C "$tmp" add -A >/dev/null 2>&1
    if ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
      echo "hygiene selftest: no longer detected: $line" >&2
      rc=1
    fi
  done <<'SAMPLES'
see docs/registry/hosts.md for the layout
described in credentials.md
provisioned by infra/scripts/create-app-lxc.sh
the vault at /root/pickle/secrets holds it
restored from secrets/origin-ca/origin.key
run deploy-api.sh after this
M6 shipped this
M4A publishing per contract v0.4.0
W1.5 lesson applied
phase roles (W3)
launch gate G5 pending
teardown on delete (B1)
review finding C4 addressed
prefill lock (R1)
S4 anti-enumeration
S13 cipher policy
O7 rotation runbook
O10 dashboard guard
discovered as H1
admin (WP-F3) queries
landed with the api-B merge
frame protocol (Lane C agreement)
보안 게이트 M-1
M4A gate mandated in contract v0.14.1 rollout
M4A gate applies to host 10.32.0.5
milestone M6 done <path d="M0 0"/>
SAMPLES

  # The opposite direction: legitimate content must pass, or the gate becomes
  # something people work around.
  cat > "$tmp/sample.txt" <<'CLEAN'
released in contract v0.14.1 to host 10.32.0.5 on D-7
migration V47__vm_request_desired_slug.sql applied
the L4 forwarder sends PROXY v2
icon <path d="M17.5 19a4.5 4.5 0 0 0 .38-8.984"/>
CLEAN
  git -C "$tmp" add -A >/dev/null 2>&1
  if ! ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
    echo "hygiene selftest: false positive on legitimate content" >&2
    rc=1
  fi

  rm -rf "$tmp"
  [ "$rc" -eq 0 ] && echo "hygiene selftest OK"
  return "$rc"
}
