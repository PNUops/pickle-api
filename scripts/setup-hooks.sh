#!/usr/bin/env bash
# Installs the repo git hooks. Run once after cloning.
set -euo pipefail
cd "$(dirname "$0")/.."
cp scripts/pre-commit.sh .git/hooks/pre-commit
cp scripts/commit-msg.sh .git/hooks/commit-msg
chmod +x .git/hooks/pre-commit .git/hooks/commit-msg
echo "hooks installed"
