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
# of cross-reference these rules forbid.
#
# Usage: hygiene_check public   # this repo is published
#        hygiene_check infra    # private-but-shared: may name vault paths

# Documentation-repo filenames. A bare mention is a reference too — that is how
# the first scrub missed most of its targets. `network.md` is deliberately absent:
# the infra repo has a runbook of the same name and the collision would make the
# gate unusable there.
HYGIENE_DOC_NAMES='internal\.md|permission-matrix\.md|hosts\.md|credentials\.md|domains-tls\.md|scheduled-jobs\.md|product-spec\.md|status\.md|architecture\.md|roadmap\.md|glossary\.md|commit-convention\.md|dev-setup\.md|production-gates\.md|backlog\.md|console-views\.md|ssh-access\.md|out-of-scope\.md|findings-triage\.md|data-model\.md|network-ipam\.md'

# Internal process vocabulary. The trailing [A-Z]? is load-bearing: M4A, W2-B.
HYGIENE_TOKENS='\b(M[0-9]+(\.[0-9]+)?[A-Z]?|W[0-9]+(\.[0-9]+)?(-[A-Z])?|G[0-9]|B[0-9]|A[0-9]|C[0-9]|R[12]|S([1-9]|1[0-3])|O([1-9]|10)|F[0-9]|H1|WP-[A-Z0-9]+|api-[A-Z]|Lane [A-Z])\b|보안 게이트|review finding|gate finding|work package'

# Whole lines to skip: graphic path data, where stripping one command would leave
# the next one (M12…) looking exactly like a milestone token.
HYGIENE_SKIP_LINES='<path|d="M|d=\{|sha512-|"integrity"'

# Substrings that merely LOOK like tokens. These are removed from a line before
# the token test, not used to suppress the whole line — a line-level exclusion
# would exempt any line that happens to also carry a version string or an IP,
# which is a large hole in a codebase full of both.
# The `$R1`/`$R2` entries are shell variable names appearing literally in a
# regex, not expansions.
# shellcheck disable=SC2016
HYGIENE_ALLOW='\bD-?(1|7|14|30)\b|V[0-9]+__|contract v[0-9.]+|v[0-9]+\.[0-9]+\.[0-9]+|[0-9]{1,3}(\.[0-9]{1,3}){3}|\bR3F\b|\bT0\b|-m[0-9]{3}|grep -m[0-9]|-w[0-9]\b|\bL4\b|PROXY v[0-9]|\bR[12]=|\$R[12]\b|"\$R[12]"'

# The files to scan. Two exclusions: this script necessarily contains every
# pattern it searches for, and lockfiles are generated content whose hashes trip
# the token pattern.
hygiene_files() {
  # Anchored on this script's own location, not the caller's cwd: verify.sh may be
  # invoked from the workspace root, which is not itself a git repository.
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  git -C "$root" ls-files -z | grep -zvE '(^|/)(hygiene\.sh|package-lock\.json|go\.sum)$'
}

hygiene_check() {
  local kind="$1" rc=0 hits

  hits=$(hygiene_files | xargs -0 grep -nIE "(\.\./docs|(^|[^a-z])docs/|${HYGIENE_DOC_NAMES})" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "hygiene: reference to the documentation repository:" >&2
    echo "$hits" >&2
    rc=1
  fi

  if [ "$kind" = public ]; then
    hits=$(hygiene_files | xargs -0 grep -nIE '(\binfra/|/root/pickle/secrets|\bsecrets/api\.env|deploy-(api|console|sshgw|proxy-agent)\.sh|create-(app|sshgw)-lxc\.sh|apply-(log-retention|tls-ciphers|terminal-ingress)\.sh|smoke-[a-z-]+\.sh|sync-systemd-units\.sh|cron-wrap\.sh|health-check\.sh)' 2>/dev/null || true)
    if [ -n "$hits" ]; then
      echo "hygiene: reference to the private infrastructure repository or the secret vault:" >&2
      echo "$hits" >&2
      rc=1
    fi
  fi

  hits=$(hygiene_files | xargs -0 grep -nIE "$HYGIENE_TOKENS" 2>/dev/null \
    | grep -vE "$HYGIENE_SKIP_LINES" \
    | while IFS= read -r hit; do
        # Re-test with the lookalikes removed: a line keeps its hit only if a real
        # token survives, so "M4A ... v0.14.1" still fails while "v0.14.1" alone passes.
        if printf '%s' "$hit" | sed -E "s/${HYGIENE_ALLOW}//g" | grep -qE "$HYGIENE_TOKENS"; then
          printf '%s\n' "$hit"
        fi
      done || true)
  if [ -n "$hits" ]; then
    echo "hygiene: internal process token (state the fact instead):" >&2
    echo "$hits" >&2
    rc=1
  fi

  [ "$rc" -eq 0 ] && echo "hygiene OK"
  return "$rc"
}

# Proves the patterns still detect what they are meant to detect. Without this a
# weakened pattern passes silently — which already happened once: `S1[0-3]` matched
# neither S1 nor S9, and a line-level exclusion let any line carrying a version
# string through. Runs on a fixed sample, so it needs no repository.
hygiene_selftest() {
  local bad rc=0
  local samples=(
    'see docs/registry/hosts.md for the layout'
    'described in credentials.md'
    'provisioned by infra/scripts/create-app-lxc.sh'
    'the vault at /root/pickle/secrets holds it'
    'run deploy-api.sh after this'
    'M6 shipped this'
    'M4A publishing per contract v0.4.0'
    'W1.5 lesson applied'
    'phase roles (W3)'
    'launch gate G5 pending'
    'teardown on delete (B1)'
    'review finding C4 addressed'
    'prefill lock (R1)'
    'S4 anti-enumeration'
    'S13 cipher policy'
    'O7 rotation runbook'
    'O10 dashboard guard'
    'discovered as H1'
    'admin (WP-F3) queries'
    'landed with the api-B merge'
    'frame protocol (Lane C agreement)'
    '보안 게이트 M-1'
    'M4A gate mandated in contract v0.14.1 rollout'
    'M4A gate applies to host 10.32.0.5'
  )
  for bad in "${samples[@]}"; do
    if ! printf '%s' "$bad" \
        | sed -E "s/${HYGIENE_ALLOW}//g" \
        | grep -qE "(\.\./docs|(^|[^a-z])docs/|${HYGIENE_DOC_NAMES}|\binfra/|/root/pickle/secrets|deploy-(api|console|sshgw|proxy-agent)\.sh|create-(app|sshgw)-lxc\.sh|${HYGIENE_TOKENS})"; then
      echo "hygiene selftest: pattern no longer detects: $bad" >&2
      rc=1
    fi
  done
  # And the opposite: legitimate content must not trip it.
  local good=(
    'released in contract v0.14.1 to host 10.32.0.5 on D-7'
    'migration V47__vm_request_desired_slug.sql applied'
    'the L4 forwarder sends PROXY v2'
  )
  for bad in "${good[@]}"; do
    if printf '%s' "$bad" | sed -E "s/${HYGIENE_ALLOW}//g" | grep -qE "$HYGIENE_TOKENS"; then
      echo "hygiene selftest: false positive on legitimate text: $bad" >&2
      rc=1
    fi
  done
  [ "$rc" -eq 0 ] && echo "hygiene selftest OK"
  return "$rc"
}
