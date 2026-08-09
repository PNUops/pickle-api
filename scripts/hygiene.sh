#!/usr/bin/env bash
# Publication-hygiene gate. Sourced by scripts/verify.sh, so a violation fails
# before it can be committed.
#
# Why it exists: this repo is published. The rules below were enforced by hand
# twice (2026-07-24, 2026-07-26) and both passes missed real violations —
# including a whole class, because a word-boundary pattern never matched a
# letter suffix like M9Z. A grep that runs on every verify is the only version
# of this that stays true.
#
# Design constraint: the gate itself is published, so it must not catalogue the
# private world it protects. Rules are therefore shapes, not inventories — "any
# .md file this repo does not contain", "any deploy-*.sh script" — and the
# self-test samples are synthetic sentences that merely have the right shape.
#
# The script is duplicated per repo on purpose: a shared copy would have to live
# somewhere central, and pointing at it from a published repo is itself the kind
# of cross-reference these rules forbid.
#
# Every copy must be byte-identical. Once they were not: they drifted for twelve
# days into several generations at once, and the rules added most recently — the
# ones written because a whole class of violation was getting through — were
# present in some copies and missing from others. Editing one copy without the
# rest is how that happens.
#
# Usage: hygiene_check public   # this repo is published
#        hygiene_check infra    # private-but-shared: may name vault paths

# The private repo and the secret vault, by path shape. Script names are matched
# by their prefix families (deploy-/smoke-/apply-/create-/sync-), not by name;
# a private script mentioned with its path is caught by the infra/ rule anyway.
HYGIENE_PRIVATE='(\binfra/|pickle/secrets|(^|[^a-z])secrets/|\b(deploy|smoke|apply|create|sync|provision)-[a-z][a-z-]*\.sh\b)'

# Internal process vocabulary, in the case it is normally written. The trailing
# [A-Z]? is load-bearing: M9Z, W2-B.
HYGIENE_TOKENS='\b(M[0-9]+(\.[0-9]+)?[A-Z]?|W[0-9]+(\.[0-9]+)?(-[A-Z])?|G[0-9]|B[0-9]|A[0-9]|C[0-9]|R[12]|S([1-9]|1[0-3])|O([1-9]|10)|F[0-9]|H1|WP-[A-Z0-9]+|api-[A-Z]|Lane [A-Z])\b'

# The same vocabulary in lowercase — the case the pattern above can never see,
# so a token that survives into an identifier survives the gate. A plain `grep
# -i` is not the fix: lowercased, every one of these tokens is also ordinary
# code (c1 a connection, s2 a state, -f2 a cut field, <h1> a heading, .m2 the
# maven home, w3.org in a URL), and the gate would drown in them. A lowercase
# token therefore has to look like a word in running text: it starts the line —
# or, once grep -Hn has prefixed the file and line, follows that colon — or
# follows a space or an opening bracket, never a quote, dot, slash or dash,
# which is where identifiers live; and it ends at a word end or at a camelCase
# hump, the shape that catches m7Rejects. A trailing dot counts only when a
# letter does not follow it, so `shipped in m6.` is caught while `c1.send(` is
# not. Digits are capped at two because milestones and waves are numbered in
# tens, while uncapped digits matched password fixtures (m1234567) and
# subdomains (m365). `_` deliberately does not end a token: \b does not end one
# either (it counts _ as a word character), so M7_x is not caught in capitals
# and the lowercase rule matches that reach rather than inventing its own.
# shellcheck disable=SC2016
HYGIENE_TOKEN_BODY_LC='m[0-9]{1,2}(\.[0-9]+)?[a-z]?|w[0-9]{1,2}(\.[0-9]+)?(-[a-z])?|g[0-9]|b[0-9]|a[0-9]|c[0-9]|r[12]|s([1-9]|1[0-3])|o([1-9]|10)|f[0-9]|h1|wp-[a-z0-9]+|api-[a-z]|lane [a-z]'
# The opening context is start-of-line, the colon `grep -Hn` puts in front of
# the content, or a space — optionally followed by a bracket that a space
# opened. That last detail is what separates prose from a call: in
# `phase roles (g5)` the `(` follows a space, in `draw(g1, ctx)` it follows an
# identifier, and only the first is text.
#
# The closing context is a camelCase hump (`m7Rejects`), a sentence-ending dot,
# a comma or colon that a space or line end follows, a space followed by
# anything that is not an operator, or the line end. A bracket-opened token may
# also close on its bracket. What this deliberately excludes is the shape code
# uses: `c1 = …`, `c1;`, `foo(a1, b2)`, `s2 += 1`. A short lowercase name
# standing alone is a token in prose and a variable in code, and only the
# characters around it say which — so the rule reads them instead of listing
# the names, which would have to grow forever.
HYGIENE_TOKENS_LC='(^|:| )[([]('"$HYGIENE_TOKEN_BODY_LC"')([])]|[A-Z]|\.([^A-Za-z]|$)| [^=+*/<>&|%^~]| $|$)|(^|:| )('"$HYGIENE_TOKEN_BODY_LC"')([A-Z]|\.([^A-Za-z]|$)|[,:]( |$)| [^=+*/<>&|%^~]| $|$)'

# A path is not running text: its word separators are / _ . and -, so the same
# vocabulary needs its own opening set there. Without it `notes/m9z-rollout.txt`
# would pass while `notes/M9Z-rollout.txt` fails, because \b already treats the
# slash as a word start — the capitalised rule reaches into a path and the
# lowercase one has to reach just as far.
HYGIENE_TOKENS_LC_PATH='(^|[/_.-])('"$HYGIENE_TOKEN_BODY_LC"')([A-Z]|[^A-Za-z0-9_]|$)'

# Process phrases. These are ordinary words rather than IDs, so nothing about
# them collides with code and they are matched case-blind: "Work Package"
# opening a sentence is the same leak as "work package" inside one.
HYGIENE_PHRASES='보안 게이트|review finding|gate finding|work package'

# Substrings that merely LOOK like a token or a reference. They are REMOVED from
# the line before the test, never used to suppress the line: a line-level
# exclusion exempts everything else on that line, which is how an earlier
# revision let `M9Z … contract v0.14.1` through, and how any comment sharing a
# line with an inline SVG passed. `d="…"` is stripped as a whole attribute so the
# next path command (M12…) cannot survive as a lookalike.
# `SHA256:` covers an SSH key fingerprint: what follows is base64, and a base64
# run contains every shape in the vocabulary (`SHA256:g1A4pf…` reads as g1 in
# lowercase). It is stripped by prefix rather than by blob shape so that only a
# digest, never an arbitrary long word, becomes invisible to the rules.
# The `$R1`/`$R2` entries are shell variable names appearing literally in a
# regex, not expansions. `S3`/`s3` and `-O2` are allowed because the storage
# service and the compiler flag are likelier in real code than the finding IDs
# they collide with — the storage service in either case, since it is written
# both ways.
# shellcheck disable=SC2016
HYGIENE_ALLOW='d="[^"]*"|sha512-[A-Za-z0-9+/=]*|SHA256:[A-Za-z0-9+/=]*|"integrity"|<path|\bD-?(1|7|14|30)\b|V[0-9]+__|contract v[0-9.]+|v[0-9]+\.[0-9]+\.[0-9]+|[0-9]{1,3}(\.[0-9]{1,3}){3}|\bR3F\b|\bT0\b|-m[0-9]{3}|grep -m[0-9]|-w[0-9]\b|-O[0-9]\b|\b[Ss]3\b|\bL4\b|PROXY v[0-9]|\bR[12]=|\$R[12]\b|"\$R[12]"'

# Markdown files a published repo may always mention: the conventional
# uppercase repo documents. Deliberately case-sensitive — the convention is
# uppercase, and the case gap keeps every lowercase outside name detectable.
HYGIENE_MD_STANDARD='README\.md|CHANGELOG\.md|LICENSE\.md|CONTRIBUTING\.md|AGENTS\.md|CLAUDE\.md'

hygiene_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
}

# Files to scan: everything tracked except this script (it necessarily contains
# every pattern it searches for) and lockfiles (generated hashes trip the token
# pattern). Anchored on the script's own location so the caller's cwd is
# irrelevant.
hygiene_files() {
  local root
  root="$(hygiene_root)" || return 1
  git -C "$root" ls-files -z | grep -zvE '(^|/)(hygiene\.sh|package-lock\.json|go\.sum)$'
}

# Every .md name this repo may legitimately mention: its own tracked .md files
# plus the uppercase standards — as an alternation regex.
hygiene_md_allow() {
  local root
  root="$(hygiene_root)" || return 1
  {
    git -C "$root" ls-files '*.md' | sed 's|.*/||'
    printf '%s\n' "$HYGIENE_MD_STANDARD" | tr '|' '\n' | sed 's/\\\././'
  } | sort -u | sed 's/\./\\./g' | paste -sd'|' -
}

# Strip the lookalikes, then keep what still matches.
hygiene_match() {
  sed -E "s@${HYGIENE_ALLOW}@@g" | grep -E "$1"
}

# The same, for a rule whose vocabulary is case-blind (the phrases). Kept
# separate rather than folded in with a flag so that no rule can be made
# case-blind by accident: the token rules must not be.
hygiene_match_i() {
  sed -E "s@${HYGIENE_ALLOW}@@g" | grep -iE "$1"
}

# hygiene_scan GREP-FLAGS PATTERN — grep every file in $files (set by the caller)
# and print the raw hits. Returns 0 when the scan ran, 2 when it broke.
#
# The distinction matters because this is a gate: a scan that never read the tree
# produces no hits, which is indistinguishable from a clean tree by exit status
# alone. grep exits 1 on "no match" and 2 on an error, but xargs collapses
# everything in 1..125 into 123, so the status cannot tell the two apart. grep's
# stderr can — silent on a clean scan, loud on an unreadable file, a broken
# symlink or a bad pattern — so that is what decides here.
hygiene_scan() {
  local flags="$1" pattern="$2" err out
  err="$(mktemp)" || return 2
  out=$(printf '%s\0' "${files[@]}" | xargs -0 grep "$flags" -E "$pattern" 2>"$err" || true)
  if [ -s "$err" ]; then
    echo "hygiene: scan did not complete (rule /$pattern/):" >&2
    cat "$err" >&2
    rm -f "$err"
    return 2
  fi
  rm -f "$err"
  printf '%s' "$out"
}

hygiene_check() {
  local kind="$1" rc=0 hits raw paths mdallow
  local -a files

  # Fail closed: an empty file list means the scan did not run (not a git
  # worktree, git missing), which must never read as "clean".
  mapfile -d '' -t files < <(hygiene_files) || true
  if [ "${#files[@]}" -eq 0 ]; then
    echo "hygiene: no files to scan — is this a git worktree?" >&2
    return 1
  fi

  if ! raw=$(hygiene_scan -HnI "(\.\./docs|(^|[^a-z])docs/)"); then rc=1; raw=""; fi
  hits=$(printf '%s' "$raw" | hygiene_match "(\.\./docs|(^|[^a-z])docs/)" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: reference to a documentation path this repository does not contain:" >&2
    echo "$hits" >&2
    rc=1
  fi

  # Any .md mention this repo cannot resolve to one of its own files is a
  # reference to an outside document — no list of outside names required, and
  # a document that does not exist yet is caught the day it is named.
  mdallow="$(hygiene_md_allow)" || return 1
  if ! raw=$(hygiene_scan -HnoI '[A-Za-z0-9._-]+\.md\b'); then rc=1; raw=""; fi
  hits=$(printf '%s' "$raw" | grep -vE ":(${mdallow})$" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: mention of a markdown document this repo does not contain:" >&2
    echo "$hits" >&2
    rc=1
  fi

  if [ "$kind" = public ]; then
    if ! raw=$(hygiene_scan -HnI "$HYGIENE_PRIVATE"); then rc=1; raw=""; fi
    hits=$(printf '%s' "$raw" | hygiene_match "$HYGIENE_PRIVATE" || true)
    if [ -n "$hits" ]; then
      echo "hygiene: reference to the private infrastructure repository or the secret vault:" >&2
      echo "$hits" >&2
      rc=1
    fi

    # A published repo's own documents follow the uppercase convention; a
    # lowercase .md appearing IN the tree would both look like an internal doc
    # and silently join the allowlist above — refuse it outright.
    paths=$(printf '%s\n' "${files[@]}" \
      | grep -E '(^|/)[a-z][A-Za-z0-9._-]*\.md$' || true)
    if [ -n "$paths" ]; then
      echo "hygiene: lowercase markdown file in a published tree (use the uppercase convention):" >&2
      echo "$paths" >&2
      rc=1
    fi
  fi

  if ! raw=$(hygiene_scan -HnI "$HYGIENE_TOKENS|$HYGIENE_TOKENS_LC"); then rc=1; raw=""; fi
  hits=$(printf '%s' "$raw" | hygiene_match "$HYGIENE_TOKENS|$HYGIENE_TOKENS_LC" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: internal process token (state the fact instead):" >&2
    echo "$hits" >&2
    rc=1
  fi

  if ! raw=$(hygiene_scan -HnIi "$HYGIENE_PHRASES"); then rc=1; raw=""; fi
  hits=$(printf '%s' "$raw" | hygiene_match_i "$HYGIENE_PHRASES" || true)
  if [ -n "$hits" ]; then
    echo "hygiene: internal process phrase (state the fact instead):" >&2
    echo "$hits" >&2
    rc=1
  fi

  # Path names carry the same rules: a directory called M7-notes/ says as much
  # as a comment would.
  paths=$(printf '%s\n' "${files[@]}" \
    | hygiene_match "$HYGIENE_TOKENS|$HYGIENE_TOKENS_LC_PATH|$HYGIENE_PHRASES" || true)
  if [ -n "$paths" ]; then
    echo "hygiene: file or directory name carries a process token:" >&2
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
# stayed green while the plumbing was sabotaged. Every sample is synthetic: it
# has the shape of a violation, not the content of one.
# shellcheck disable=SC2030,SC2031  # the checks run in subshells by design; the
# variables they read are assigned here and never written back.
hygiene_selftest() {
  local tmp rc=0 self line bad_path
  self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/hygiene.sh"
  tmp="$(mktemp -d)" || return 1
  mkdir -p "$tmp/scripts"
  cp "$self" "$tmp/scripts/hygiene.sh"
  git -C "$tmp" init -q
  git -C "$tmp" config user.email hygiene@example.invalid
  git -C "$tmp" config user.name hygiene
  # An own tracked document, to prove the allowlist admits it below.
  echo "sample guide" > "$tmp/GUIDE.md"

  while IFS= read -r line; do
    printf '%s\n' "$line" > "$tmp/sample.txt"
    git -C "$tmp" add -A >/dev/null 2>&1
    if ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
      echo "hygiene selftest: no longer detected: $line" >&2
      rc=1
    fi
  done <<'SAMPLES'
see docs/handbook/layout.md for the layout
described in incident-playbook.md
provisioned by infra/scripts/build-image.sh
the vault at pickle/secrets holds it
restored from secrets/ca/example.key
run deploy-widgets.sh after this
gated by smoke-widgets.sh
M6 shipped this
M9Z milestone per contract v0.4.0
W1.5 lesson applied
phase roles (W3)
launch gate G5 pending
teardown step (B1)
review finding C4 addressed
prefill rule (R1)
S4 hardening item
S13 hardening item
O7 operations item
O10 operations item
discovered as H1
admin (WP-Z9) queries
landed with the api-Z merge
frame protocol (Lane Z agreement)
보안 게이트 M-1
M9Z gate mandated in contract v0.14.1 rollout
M9Z gate applies to host 10.32.0.5
milestone M6 done <path d="M0 0"/>
m6 shipped this in lowercase
m9z rollout in lowercase
w1.5 lesson applied
phase roles (w3)
launch gate g5 pending
teardown step (b1)
review finding c4 addressed in lowercase
prefill rule (r1)
s4 hardening item in lowercase
o7 operations item in lowercase
discovered as h1
admin (wp-f3) queries
landed with the api-b merge
frame protocol (lane c agreement)
void m7Rejects() {
shipped in m6.
fingerprint SHA256:g1A4pfkmf+XmceT0lCSr03Ev landed with the api-b merge
key SHA256:g1A4pfkmf+XmceT0lCSr03Ev rotated in the M9Z rollout
config value "gate g5 pending"
void m6KeysAppearInCatalog() {
w3 lesson applied here
phase roles (g5)
rolled back in m6, then re-applied
Work Package delivered
Review Finding closed
GATE FINDING still open
SAMPLES

  # A lowercase document in the tree is refused even though tracking it would
  # put it on the mention allowlist.
  echo "notes" > "$tmp/design-notes.md"
  printf 'clean line\n' > "$tmp/sample.txt"
  git -C "$tmp" add -A >/dev/null 2>&1
  if ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
    echo "hygiene selftest: lowercase tracked markdown not refused" >&2
    rc=1
  fi
  rm -f "$tmp/design-notes.md"

  # A scan that cannot read the tree must fail the gate rather than report a
  # clean run. A dangling symlink is the cheapest way to break grep for real
  # (and it breaks it for root too, unlike a permission bit).
  ln -s missing-target "$tmp/dangling"
  printf 'clean line\n' > "$tmp/sample.txt"
  git -C "$tmp" add -A >/dev/null 2>&1
  if ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
    echo "hygiene selftest: an unreadable tree still reported clean" >&2
    rc=1
  fi
  git -C "$tmp" rm -q --cached dangling >/dev/null 2>&1
  rm -f "$tmp/dangling"

  # A path carries the vocabulary as loudly as a comment does, in either case
  # and at any depth: \b already reaches into a path for the capitalised form,
  # so the lowercase rule is given the same reach (HYGIENE_TOKENS_LC_PATH).
  printf 'clean line\n' > "$tmp/sample.txt"
  while IFS= read -r bad_path; do
    [ -z "$bad_path" ] && continue
    mkdir -p "$tmp/$(dirname "$bad_path")"
    echo "nothing interesting" > "$tmp/$bad_path"
    git -C "$tmp" add -A >/dev/null 2>&1
    if ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
      echo "hygiene selftest: process token in a path not refused: $bad_path" >&2
      rc=1
    fi
    git -C "$tmp" rm -q --cached "$bad_path" >/dev/null 2>&1
    rm -rf "${tmp:?}/${bad_path%%/*}"
  done <<'PATHS'
notes/m9z-rollout.txt
notes/M9Z-rollout.txt
wp-f3/readme.txt
PATHS

  # ...but an underscore ends a token no more in a path than in running text,
  # so an ordinary name keeps working.
  mkdir -p "$tmp/nft"
  echo "package nft" > "$tmp/nft/m11_in.go"
  git -C "$tmp" add -A >/dev/null 2>&1
  if ! ( cd "$tmp" && . scripts/hygiene.sh && hygiene_check public ) >/dev/null 2>&1; then
    echo "hygiene selftest: false positive on an ordinary path" >&2
    rc=1
  fi
  git -C "$tmp" rm -q --cached nft/m11_in.go >/dev/null 2>&1
  rm -rf "${tmp:?}/nft"

  # The opposite direction: legitimate content must pass, or the gate becomes
  # something people work around.
  cat > "$tmp/sample.txt" <<'CLEAN'
released in contract v0.14.1 to host 10.32.0.5 on D-7
migration V47__vm_request_desired_slug.sql applied
the L4 forwarder sends PROXY v2
icon <path d="M17.5 19a4.5 4.5 0 0 0 .38-8.984"/>
see README.md and CHANGELOG.md for details
the local GUIDE.md covers this
	c1 := connect(t, ts, "tok-1")
	s2, err := Load(path)
<h1 className="title">Heading</h1>
grep '^KEY=' env | cut -d= -f2
nft counter m11_new beside m11_in
MAVEN_USER_HOME="${HOME}/.m2"
http://www.w3.org/2001/XMLSchema-instance
password fixture m1234567 rejected
reserved subdomain m365
the s3 bucket policy
answers 404, 422 and 503 without disclosing which
10.32.0.0/24 reaches 10.32.0.5:8443 and port 22
icon <path d="m12 2c1 0 2 1 2 2s-1 2-2 2"/>
  var c1 = connections.first();
  let s2 = state.next()
  const b1 = buffer[1]
  sheet.range(a1)
  draw(g1, ctx)
  r1 = re.compile(x)
  return c1;
  foo(a1, b2)
  if (s1 && s2) {
  let s2 += 1
  const [a1, b1] = pair
  x = f1 * 2
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
