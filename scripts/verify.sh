#!/usr/bin/env bash
# Full verification gate: build + tests. Run before every commit batch.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q verify
echo "api verify OK"

# Informational dependency audit (non-blocking, keeps verify fast). Prints
# available updates of direct dependencies so stale / vulnerable versions
# surface on every run; full CVE scanning lands with the CI pipeline.
echo "--- dependency update audit (informational) ---"
# The versions plugin reports at INFO, so no -q here; filtered to the essence.
mvn versions:display-dependency-updates -DprocessDependencyManagement=false 2>/dev/null \
    | grep -E '\.{3,}|The following|No dependencies' | tail -20 || true

echo "api dependency audit done (informational)"
