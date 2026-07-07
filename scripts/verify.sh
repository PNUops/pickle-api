#!/usr/bin/env bash
# Full verification gate: build + tests. Run before every commit batch.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q verify
echo "api verify OK"
