#!/usr/bin/env bash
# Installs this repository's git hooks: the pre-commit secret scan and the
# commit-msg format check. Run once after cloning.
#
# The hooks are copied rather than symlinked so that a checkout with no
# hooks installed simply has none, instead of half-working ones pointing at a
# path that may not exist.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"

# Ask git where the hooks live instead of assuming <repo>/.git/hooks. A linked
# worktree keeps a .git FILE, not a directory, and its hooks resolve to the main
# checkout's shared directory; assuming the path silently installed nothing.
hooks=$(git -C "$here" rev-parse --git-path hooks)
case "$hooks" in /*) ;; *) hooks="$here/$hooks" ;; esac
mkdir -p "$hooks"
install -m 0755 "$here/scripts/pre-commit.sh" "$hooks/pre-commit"
install -m 0755 "$here/scripts/commit-msg.sh" "$hooks/commit-msg"
echo "installed hooks -> $hooks/{pre-commit,commit-msg}"
