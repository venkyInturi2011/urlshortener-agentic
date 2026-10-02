#!/usr/bin/env bash
# Runs the three scenarios end to end, chained: greenfield -> brownfield (on its output) -> ambiguous (on that).
# Real Maven builds/tests of the generated service are executed at the exit gates (use SKIP_BUILD=1 to skip).
# Usage: scripts/run-all.sh            (APPROVE=auto by default; use APPROVE=scripted|interactive)
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=orchestrator/target/orchestrator.jar
[ -f "$JAR" ] || mvn -B -q -pl orchestrator package -DskipTests
APPROVE="${APPROVE:-auto}"
EXTRA=()
[ -n "${SKIP_BUILD:-}" ] && EXTRA+=(--skip-build)

rm -rf workspace/demo
mkdir -p workspace/demo

publish() { # publish <run-dir> <name>: keep the audit-grade evidence under docs/runs
  mkdir -p "docs/runs/$2"
  cp "$1/report.md" "$1/metrics.json" "$1/audit.jsonl" "$1/lineage.json" "docs/runs/$2/"
}

echo "=== 1/3 greenfield"
java -jar "$JAR" run --scenario greenfield --target workspace/demo/v1 --run-dir workspace/demo/run-greenfield --approve "$APPROVE" "${EXTRA[@]}"
java -jar "$JAR" verify-audit --run-dir workspace/demo/run-greenfield
publish workspace/demo/run-greenfield greenfield

echo "=== 2/3 brownfield (on a copy of the greenfield output)"
java -jar "$JAR" run --scenario brownfield --copy-from workspace/demo/v1 --target workspace/demo/v2 --run-dir workspace/demo/run-brownfield --approve "$APPROVE" "${EXTRA[@]}"
java -jar "$JAR" verify-audit --run-dir workspace/demo/run-brownfield
publish workspace/demo/run-brownfield brownfield

echo "=== 3/3 ambiguous (on a copy of the brownfield output)"
java -jar "$JAR" run --scenario ambiguous --copy-from workspace/demo/v2 --target workspace/demo/v3 --run-dir workspace/demo/run-ambiguous --approve "$APPROVE" "${EXTRA[@]}"
java -jar "$JAR" verify-audit --run-dir workspace/demo/run-ambiguous
publish workspace/demo/run-ambiguous ambiguous

echo "Done. Generated services: workspace/demo/v1, v2, v3. Evidence: docs/runs/*"
