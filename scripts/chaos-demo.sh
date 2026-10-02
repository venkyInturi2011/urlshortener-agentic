#!/usr/bin/env bash
# Failure-handling demos on the brownfield scenario. Each starts from a fresh copy of the greenfield output.
#   1. retry + rollback : an agent emits broken Java once; the exit gate rejects it, the change is rolled back, the retry succeeds
#   2. safe-stop+resume : primary AND fallback fail -> run halts; downstream is skipped; `resume` finishes it after the "fix"
#   3. policy block     : an agent injects a hard-coded secret -> blocked by policy, never written, run halts
#   4. human rejection  : the approver answers "n" at the design gate -> nothing is implemented
set -uo pipefail
cd "$(dirname "$0")/.."

JAR=orchestrator/target/orchestrator.jar
[ -f "$JAR" ] || mvn -B -q -pl orchestrator package -DskipTests
BASE=workspace/demo/v1
[ -d "$BASE" ] || { echo "run scripts/run-all.sh first (needs $BASE)"; exit 1; }
rm -rf workspace/chaos
EXTRA=()
[ -n "${SKIP_BUILD:-}" ] && EXTRA+=(--skip-build)

publish() { mkdir -p "docs/runs/$2"; cp "$1/report.md" "$1/metrics.json" "$1/audit.jsonl" "$1/lineage.json" "docs/runs/$2/"; }
run() { # run <name> <extra args...>
  local name=$1; shift
  java -jar "$JAR" run --scenario brownfield --copy-from "$BASE" --target "workspace/chaos/$name-target" \
    --run-dir "workspace/chaos/$name" "$@" "${EXTRA[@]}"
}

echo "=== 1. retry + rollback"
run retry-rollback --approve auto --inject-failure impl_service_logic:1:bad-output
publish workspace/chaos/retry-rollback chaos-retry-rollback

echo "=== 2. safe-stop, then resume"
run safe-stop --approve auto --inject-failure impl_persistence:9:exception:both
echo "exit code (2 = halted): $?"
publish workspace/chaos/safe-stop chaos-safe-stop-halted
java -jar "$JAR" resume --run-dir workspace/chaos/safe-stop --approve auto "${EXTRA[@]}"
publish workspace/chaos/safe-stop chaos-safe-stop-resumed

echo "=== 3. policy block"
run policy --approve auto --inject-failure impl_api_contract:1:policy
publish workspace/chaos/policy chaos-policy-block

echo "=== 4. human rejection"
printf 'n\n' | run rejected --approve interactive
publish workspace/chaos/rejected chaos-human-rejection
