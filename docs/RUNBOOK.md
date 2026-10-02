# Operator runbook

## Start a run

```bash
java -jar orchestrator/target/orchestrator.jar run --scenario <name|file.yaml> --target <dir> [--copy-from <baseline>] [--approve interactive]
```

Run directory (default `workspace/runs/<scenario>-<timestamp>`; override with `--run-dir`):

| File | Content |
|---|---|
| `report.md` | Human-readable summary: graph, node table, metrics, decisions, lineage, artifact versions |
| `audit.jsonl` | Hash-chained event log (source of truth) |
| `metrics.json` | Reliability metrics |
| `lineage.json` | Executions (consumed/produced versions) and decisions |
| `state.json`, `context.json`, `snapshots/` | Resume state: node statuses, artifacts, per-node rollback snapshots |
| `logs/<node>-mvn-<goal>.log` | Full Maven output of a build gate |
| `run.json` | Options of the run (used by `resume`) |

## Approving work

With `--approve interactive` the console shows the node, reason, summary, changed paths and high-impact paths:

```
=== APPROVAL REQUIRED: impl_persistence ===
Reason : high-impact changes: [schema migration]
...
Approve? [y/N]
```

Only `y`/`yes` approves. Empty input, EOF, or anything else **denies** and halts the run. Review the changed files and
the reason before answering; the decision, approver and comment are written to the audit log.

## A run halted (exit code 2)

1. `report.md` header gives `HALTED (<reason>)`; the nodes table shows which node is `FAILED` and which are `SKIPPED`.
2. Find the cause in the audit log:
   ```bash
   grep -E '"type":"(SAFE_STOP|NODE_FAILED|POLICY_BLOCKED|APPROVAL_DENIED|VALIDATION)"' <run-dir>/audit.jsonl
   ```
3. For a failed build gate read `logs/<node>-mvn-test.log` (or `-compile.log`).
4. Typical causes and actions:

   | Symptom | Action |
   |---|---|
   | `policy violation: … hardcoded secret` | Fix the agent/template; **do not** loosen the rule to get past it |
   | `approval denied by …` | Intended stop. Change the requirement or re-run and approve |
   | `structure failed` / `mvn … failed` after fallback | Fix the template/scenario, then `resume` |
   | `attempt budget exceeded` / `runtime budget exceeded` | Investigate the loop; raise budgets deliberately in `EngineConfig` |
   | `entry gate failed: missing inputs` | Scenario wiring error: a `consumes` artifact is not produced upstream |
5. `java -jar orchestrator/target/orchestrator.jar resume --run-dir <run-dir>` continues. Completed nodes are not
   repeated; `FAILED`/`SKIPPED`/`RUNNING` nodes restart from `PENDING`. A leftover `STOP` file is removed on resume.

## Emergency stop

Create a file called `STOP` in the run directory (`touch <run-dir>/STOP`). The engine stops scheduling, lets in-flight
attempts end, marks unreached nodes `SKIPPED` and records `SAFE_STOP`. Files already applied by completed nodes stay;
to undo them use the snapshots (`snapshots/<node>.json` hold the pre-change content) or restore from version control.

## Verify the history

```bash
java -jar orchestrator/target/orchestrator.jar verify-audit --run-dir <run-dir>   # VALID / INVALID (exit 3)
```
Store the head hash (`hash` of the last line) somewhere the run host cannot write if you need tamper-*proofing*.

## Troubleshooting

| Problem | Fix |
|---|---|
| `could not run mvn` | Maven must be on `PATH` (on Windows the validator calls `cmd /c mvn`); or use `--skip-build` (release readiness will be CONDITIONAL) |
| First build is slow | Maven downloads Spring Boot dependencies once; later runs reuse `~/.m2` |
| `--copy-from requires an empty or missing target` | Choose a new `--target`; the orchestrator never merges into an existing project |
| Port 8080 in use when running the service | `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9090` and set `shortener.base-url` accordingly |
| Git reports "Filename too long" on Windows | Keep the repository path short, or `git config core.longpaths true` |

## Add a scenario / agent / rule

See [ARCHITECTURE §12](ARCHITECTURE.md#12-extending). Always add a test that exercises the new element through
`WorkflowEngineTest`-style fake agents before using it on a real project.
