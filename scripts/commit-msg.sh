#!/usr/bin/env bash
# Enforces the basic commit-message rules on every commit message.
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
# Hangul syllables U+AC00-U+D7A3, spelled as their UTF-8 bytes under LC_ALL=C.
# The obvious spellings are each wrong on half the machines that run this hook,
# and both fail silently. `grep -qP` is a GNU extension: BSD grep exits 2 with
# "invalid option", the `if` reads false, and the warning never fired on a
# developer's machine at all. `grep -qE '[가-힣]'` looks portable and is not --
# a glibc range expression follows collation order, so under en_US.UTF-8 on the
# platform host it matches nothing, while under LC_ALL=C it degenerates to bytes
# and flags an em dash, CJK, or an accented Latin letter. Byte ranges in the C
# locale mean the same thing to both greps. Standalone jamo are out of the block
# here exactly as they were before.
hangul=$'\xea[\xb0-\xbf][\x80-\xbf]|[\xeb\xec][\x80-\xbf][\x80-\xbf]|\xed[\x80-\x9d][\x80-\xbf]|\xed\x9e[\x80-\xa3]'
if printf '%s' "$subject" | LC_ALL=C grep -qE "$hangul"; then
  warn "Korean in subject; English is the default"
fi
exit 0
