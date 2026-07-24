#!/usr/bin/env bash
# Enforces the basic commit-message convention on every commit message.
# Hard failures: missing/unknown type prefix, subject > 72 chars, trailing period.
# Warnings (non-blocking): enumeration patterns, em dash, Korean in the subject.
set -euo pipefail

msg_file="$1"
subject=$(sed -n '1p' "$msg_file")

# git-generated autosquash subjects pass through untouched
case "$subject" in
  fixup!*|squash!*) exit 0 ;;
esac

fail() {
  echo "commit-msg: $1" >&2
  echo "  subject: $subject" >&2
  echo "  format: 'type: subject' (feat|fix|docs|test|chore|refactor|perf|build|style|ci|revert|merge), imperative, <=72 chars, no trailing period" >&2
  exit 1
}

if ! printf '%s' "$subject" | grep -qE '^(feat|fix|docs|test|chore|refactor|perf|build|style|ci|revert|merge): [^ ]'; then
  fail "subject must be 'type: subject' with a known type (feat fix docs test chore refactor perf build style ci revert merge)"
fi
if [ "${#subject}" -gt 72 ]; then
  fail "subject is ${#subject} chars (hard limit 72, aim ~50)"
fi
case "$subject" in
  *.) fail "subject must not end with a period" ;;
esac

warn() { echo "commit-msg (warning): $1" >&2; }
if printf '%s' "$subject" | grep -q '—'; then
  warn "em dash in subject; prefer comma/colon/parentheses"
fi
if printf '%s' "$subject" | grep -qE '\([^)]*,[^)]*\)'; then
  warn "parenthetical list in subject; state one main change"
fi
if printf '%s' "$subject" | grep -qE ',[^,]*,'; then
  warn "multiple commas in subject; avoid 'A, B, and C' enumerations"
fi
if printf '%s' "$subject" | grep -qP '[\x{AC00}-\x{D7A3}]'; then
  warn "Korean in subject; English is the default"
fi
exit 0
