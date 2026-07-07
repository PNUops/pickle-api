#!/usr/bin/env bash
# Installs the repo git hooks. Run once after cloning.
set -euo pipefail
cd "$(dirname "$0")/.."
cp scripts/pre-commit.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
echo "hooks installed"
