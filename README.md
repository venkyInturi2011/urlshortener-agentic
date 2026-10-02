# Agentic SDLC Orchestrator + URL Shortener

A working prototype of **controlled-autonomy software delivery**: a requirement goes in, a reviewable engineering
outcome comes out (design, code, tests, docs, release assessment), driven by an orchestration layer that is
**stateful, graph-based, governed and auditable**. The system under construction is a URL shortener, which the
agents build (greenfield), evolve (brownfield) and adapt to a vague requirement (ambiguous).

> Agents execute under defined autonomy boundaries; humans own oversight, approvals, and final quality.

| | |
|---|---|
| **Orchestrator** | `orchestrator/` – plain Java 17, ~2.8 kLOC, 111 tests, 97 % line coverage |
| **Generated service** | `shortener-service/` – Spring Boot 3.2 / H2, produced *by* the greenfield run (71 tests) |
| **Evidence** | `docs/runs/` – reports, hash-chained audit logs, metrics and lineage of real runs |

## What it demonstrates

| Requirement of the assignment | Where it lives |
|---|---|
| Explicit dependency graph, entry/exit gates, parallel paths with synchronisation | `graph/WorkflowGraph`, `engine/WorkflowEngine` |
| Stateful, non-linear execution, resumable | ready-set scheduler, `state.json`, `resume` command |
| Cross-stage context and decision lineage | `context/ContextStore` (versioned, content-hashed artifacts), `lineage.json` |
| Human approval for high-impact actions | `approval/*`, engine-side `policy/ChangeClassifier` |
| Bounded retries, fallback, rollback, safe-stop | `WorkflowEngine`, `resilience/*` |
| Policy guardrails (security, compliance, change control) | `policy/*` – run on *every* proposed change |
| Audit-grade observability | `audit/AuditLog` – hash-chained JSONL, `verify-audit` |
| Reliability metrics (success rate, retries/rollbacks, MTTR, latency) | `metrics/MetricsCollector` → `metrics.json` |
| Dynamic re-planning when upstream outputs change | `engine/Replanner` (data-flow invalidation + rollback) |
| Greenfield / brownfield / ambiguous scenarios | `orchestrator/src/main/resources/scenarios/*.yaml`, [docs/SCENARIOS.md](docs/SCENARIOS.md) |

API reference: [docs/API.md](docs/API.md) ([OpenAPI](docs/openapi.yaml)). Design in depth: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Honest accounting of risks and limits:
[docs/RISKS.md](docs/RISKS.md). Final summary: [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md).

## Setup

Prerequisites: **JDK 17+** and **Maven 3.9+** (internet access for the first dependency download).
Works on Windows (Git Bash / PowerShell), macOS and Linux. No API keys or network services are needed.

```bash
git clone <this repo> && cd urlshortener-agentic
mvn clean verify                 # builds the orchestrator, runs 111 tests, enforces >= 85 % line coverage
mvn -f shortener-service/pom.xml verify    # optional: build + test the committed greenfield output (71 tests)
```

## Run the three scenarios end to end

```bash
bash scripts/run-all.sh          # greenfield -> brownfield -> ambiguous, with REAL mvn compile/test gates (~1 min)
bash scripts/chaos-demo.sh       # retry+rollback, safe-stop+resume, policy block, human rejection
```

`run-all.sh` writes the generated services to `workspace/demo/v1`, `v2`, `v3` (each stage is built and tested by
the orchestrator itself) and publishes the evidence to `docs/runs/`. Approvals default to `--approve auto` so the
demo is unattended; use `APPROVE=interactive bash scripts/run-all.sh` to be the human in the loop.

Individual runs (after `mvn -pl orchestrator package -DskipTests`):

```bash
java -jar orchestrator/target/orchestrator.jar run --scenario greenfield --target workspace/my-service --approve interactive
java -jar orchestrator/target/orchestrator.jar run --scenario brownfield --copy-from workspace/my-service --target workspace/my-service-v2
java -jar orchestrator/target/orchestrator.jar verify-audit --run-dir workspace/runs/<run-id>
```

### CLI reference

| Command | Purpose |
|---|---|
| `run --scenario <greenfield\|brownfield\|ambiguous\|file.yaml> --target <dir>` | Execute a workflow into `<dir>` |
| `--copy-from <dir>` | Start from a copy of an existing project (brownfield baseline); refuses a non-empty target |
| `--approve scripted\|auto\|interactive` | `scripted` = decisions in the scenario file (default, denies unknown); `auto` approves all (demos only, still audited); `interactive` prompts on the console and **fails closed** |
| `--skip-build` | Skip the Maven validators (recorded as *not executed*; release readiness degrades to CONDITIONAL) |
| `--inject-failure node:times:mode[:both]` | Fault injection. `mode` = `exception` \| `bad-output` \| `policy`; `both` also fails the fallback agent |
| `--max-retries N`, `--parallelism N`, `--run-dir <dir>` | Tuning |
| `resume --run-dir <dir>` | Continue a halted run; completed nodes are not repeated |
| `verify-audit --run-dir <dir>` | Recompute the audit hash chain (exit code 3 if tampered) |
| `graph --scenario <name>` | Print the workflow graph as Mermaid |

Kill switch: create a file named `STOP` in a run directory; the engine stops scheduling at the next opportunity.
Exit codes: `0` completed, `2` halted (safe-stop), `3` audit chain invalid, `1` usage.

### Run each generated service

```bash
cd workspace/demo/v3            # or shortener-service/ for the committed greenfield output
SHORTENER_ADMIN_KEY=change-me mvn spring-boot:run
curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com/docs","expiresAt":"2030-01-01T00:00:00Z"}'
curl -i localhost:8080/<code>                       # 302
curl -s localhost:8080/api/v1/urls/<code>/stats     # {"totalClicks":1,"clicksByDay":{...}}
curl -i -X DELETE -H 'X-API-Key: change-me' localhost:8080/api/v1/urls/<code>   # 204, then redirect is 410
```

Verified against the final (`v3`) service: create → 201, redirect → 302, stats with per-day clicks, plain `http://`
and SSRF targets (`169.254.169.254`) → 400, denylisted domain → 400, `Admin` alias → 400 (the brownfield bug fix),
delete without key → 403, with key → 204, redirect afterwards → 410.

## Repository map

```
orchestrator/                 the differentiator (plain Java, no framework)
  graph/ engine/ context/ audit/ policy/ approval/ resilience/ metrics/ validate/ agent/ cli/
  src/main/resources/scenarios/   greenfield.yaml  brownfield.yaml  ambiguous.yaml
  src/main/resources/templates/   vetted code templates the implementer/tester agents render (v1, v2, v3)
shortener-service/            greenfield output (v1.0.0), committed as the reference artefact
docs/                         ARCHITECTURE, SCENARIOS, TESTING, RISKS, RUNBOOK, ENGINEERING_SUMMARY, runs/
scripts/                      run-all.sh, chaos-demo.sh
workspace/                    run output (git-ignored)
```

## Important honesty notes

* The **agents are deterministic and rule/template based**, not LLM calls. They sit behind the `Agent` interface
  (`name()`, `execute(TaskContext)`), which is where an LLM-backed agent would plug in; none is shipped, so
  "code generation" here means *selecting and parameterising vetted templates* from requirement artifacts. This was a
  deliberate trade-off for reproducibility and offline execution – see [docs/RISKS.md](docs/RISKS.md).
* The governance machinery (policy, approvals, snapshots, validators, audit, replanning) is agent-agnostic and is the
  part that would carry over unchanged to LLM agents.
* Several defects were found by the system's own gates and tests while building it (test-compile error, reserved SQL
  word, retry-budget bypass, ...) – listed in [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md).
* **The committed `shortener-service/` is the v1.0.0 greenfield output and contains one deliberately planted, documented
  defect** (case-sensitive reserved-alias check) that the brownfield scenario finds and fixes. Run `scripts/run-all.sh`
  to get the fixed versions in `workspace/demo/v2` and `v3`. Do not deploy v1 ([docs/RISKS.md](docs/RISKS.md), S12).
