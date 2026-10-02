# Final engineering summary

## 1. Problem and approach

**Ask:** a working prototype that turns a requirement into a reviewable engineering outcome through an agentic,
governed SDLC workflow – with an orchestration layer (explicit dependency graph, gates, parallel paths and joins,
lineage, approvals, bounded retry/fallback/rollback/safe-stop, policy guardrails, audit, metrics, re-planning) –
demonstrated on a URL shortener in greenfield, brownfield and ambiguous scenarios.

**Approach:** treat the orchestrator as the product and the URL shortener as the workload.
Agents are narrow, replaceable proposers; **all control lives in the engine**. To make every claim testable the agents
are deterministic (rule- and template-based) and the governance is exercised against real builds and real failures.

## 2. Plan and rationale (as executed)

| Step | Output | Why this order |
|---|---|---|
| 1. Model + graph + context + audit | immutable artifacts, DAG with data-flow closure, hash chain | Everything else depends on these invariants |
| 2. Governance primitives | policy rules, impact classifier, approval gates, snapshots, safe-stop, fault injector | Controls before capability |
| 3. Engine | ready-set scheduler, governed node pipeline, events + re-plan, persistence, reporting | Wires the primitives together |
| 4. Agents + template library (v1/v2/v3) | requirements, architect, risk, reviewer, impact analyst, implementer/tester, documenter, release | Needed to run real scenarios |
| 5. Scenarios + CLI | 3 YAML workflows, `run/resume/verify-audit/graph` | Makes the system operable |
| 6. Tests | 111 orchestrator tests (97 % lines) + 71/87/98 service tests | Prove the controls, not just the happy path |
| 7. Real runs + chaos | `docs/runs/*` | Evidence rather than assertion |
| 8. Documentation | this folder | Reviewability |

## 3. Artifacts

* `orchestrator/` – ~2.8 kLOC main, ~1.9 kLOC tests; 55 service templates in 13 change sets.
* `shortener-service/` – the greenfield output (API, validation, rate limiting, analytics, repositories, schema, tests, OpenAPI, risk register, README, CHANGELOG, release readiness).
* `docs/` – ARCHITECTURE, SCENARIOS, TESTING, RISKS, RUNBOOK, this summary; `docs/runs/` – audit-grade evidence of 3 scenario runs and 4 failure demos (hash-chained audit logs verified).
* `scripts/` – one-command scenario and chaos demos.

## 4. Results

| Evidence | Result |
|---|---|
| Greenfield / brownfield / ambiguous | all `COMPLETED`; node success rate 1.0; audit chains valid (97 / 94 / 114 records) |
| Generated service tests | 71 → 87 → 98, all passing, executed *by the orchestrator* at the exit gates |
| Live smoke test of final service | create 201, redirect 302, stats with per-day clicks, SSRF/http/denylist/`Admin` → 400, delete 403→204→410 |
| Retry + rollback | broken output rejected by gate, rolled back, retry succeeded; `mttr_ms = 5018` |
| Fallback → safe-stop → resume | halted with exit 2, 6 nodes done; resume finished without repeating them |
| Policy block | secret injected → blocked on attempt 1, nothing written |
| Human rejection | halted before any code change |
| Re-plan | 5 nodes invalidated and rolled back newest-first, 2 retained; design re-approved; output regenerated HTTPS-only |

Reliability metrics reported per run: node success rate, first-attempt success rate, retries, fallbacks, rollbacks,
policy blocks, replans, MTTR, unrecovered incidents, approvals and wait time, e2e and per-node latency.

## 5. Defects found while building it, and what caught them

Honest accounting – most were caught by the gates and tests this project is about:

| # | Defect | Caught by | Fix |
|---|---|---|---|
| 1 | Generated unit test used `"a".repeat(32)` in an annotation (does not compile) | `verify` node's `mvn test` gate on the first greenfield run (run halted) | Template fixed |
| 2 | `day` is a reserved word in H2 2.x; per-day SQL failed at runtime | Generated integration tests via the brownfield `verify` gate | Column aliased `click_day` |
| 3 | `MavenValidator` crashed while reporting #1/#2 (console charset ≠ UTF-8) | Same run – the failure report itself failed | Log read as ISO-8859-1 |
| 4 | Impact classifier flagged updated *tests* under `/api/` as API-contract changes (5 approvals instead of 4) | Reading the approvals in the audit log | Production-only rule + unit test |
| 5 | Requirement analyzer called "safety (SSRF)" ambiguous | Unit test on the greenfield text | `unless` patterns for already-explicit terms |
| 6 | Global attempt budget was not enforced inside a node's own retry loop | `attemptBudgetStopsRunawayRetries` | Check at every retry boundary |
| 7 | `nodes_done` exceeded `nodes_total` after a re-plan | Ambiguous demo output | Final-state count + separate `node_executions_completed` |
| 8 | Duplicate `SAFE_STOP` audit records from a race | Design review before first run | Single tracing point in the scheduler loop |

One defect was **planted deliberately**: the v1 service's reserved-alias check is case-sensitive (see RISKS S12), to
give the brownfield scenario a real bug to find through impact analysis and regression tests. The committed
`shortener-service/` is that v1 output and should not be deployed.

## 6. Risks, trade-offs, validation

See [RISKS.md](RISKS.md) (risk registers for orchestrator and service, failure-scenario table, trade-offs,
assumptions) and [TESTING.md](TESTING.md) (validation layers and gaps). The three that matter most:

1. **Agents are not LLMs.** The governance is real and exercised; the "intelligence" is a deterministic stand-in. Swapping in an
   LLM agent changes agent quality, not the control plane – but would add prompt-injection and non-determinism concerns
   that the existing "agent output is untrusted" stance (policy, approval, gates) is designed for.
2. **Tamper-evident, not tamper-proof**, and approvals are not authenticated – adequate for a prototype, not for audit
   in a regulated environment without external anchoring and identity.
3. **SSRF defence is URL-level only** (no DNS resolution) and the generated service has a single static admin key.

## 7. Assumptions

Listed in [RISKS §E](RISKS.md#e-assumptions). In short: trusted requirement text, untrusted agent output,
Maven/Java text projects, disjoint file sets for parallel nodes, accurately declared `consumes`/`produces`, and
available Maven + network when build gates are on.

## 8. Limitations and next steps

Highest-value next steps, in order: (1) LLM-backed agents behind the existing interface with prompt-injection tests;
(2) authenticated approvals with separation of duties and an external audit anchor; (3) persisted in-flight snapshots +
overlap detection for parallel nodes; (4) VCS-backed snapshots and PR-based delivery; (5) distributed execution with
leases; (6) metrics/trace export; (7) for the service: DNS-aware SSRF protection, Flyway, shared rate limiting,
asynchronous click ingestion, per-user auth.

## 9. How the evaluation criteria map

| Criterion | Where to look |
|---|---|
| Effectiveness of agentic orchestration | ARCHITECTURE §3–§6; `WorkflowEngineTest`; `docs/runs/*/report.md` |
| Architecture / design quality | ARCHITECTURE §1, §10; package structure with single-purpose classes and small interfaces |
| Depth of decomposition and execution | SCENARIOS (node tables, gates, joins, impact analysis) |
| Realism / quality of outputs | `shortener-service/` and `workspace/demo/v3` (built, tested, run live); OpenAPI; risk register |
| Validation and risk rigor | TESTING §3, RISKS A–C, section 5 above |
| Clarity and defensibility of decisions | ARCHITECTURE §10 (decisions vs rejected alternatives), RISKS D |
| Modular, testable, reliable, secure, scalable, safe change | 111 tests / 97 % coverage gate, policy + approval + rollback; scale path in ARCHITECTURE §11 |
| Engineering judgment | Choosing determinism over a demo-ware LLM call; fail-closed defaults; honest limitation lists; deliberately seeded and disclosed defect |
