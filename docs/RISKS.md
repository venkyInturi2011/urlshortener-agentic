# Risks, trade-offs, assumptions and limitations

## A. Risk register – the orchestration layer

| ID | Risk / failure scenario | Likelihood / impact | Control in place | Residual |
|---|---|---|---|---|
| O1 | An agent proposes harmful or sloppy changes (secrets, command exec, path escape, new dependencies) | Med / High | Policy runs **before** any write; engine-side impact tiers; dependency allow-list; size/type limits; blocks are *not retried* and halt the run | Pattern-based: a determined or novel pattern can slip through (no semantic analysis) |
| O2 | Agent understates impact to avoid approval | Med / High | Impact classified by the engine from path + kind, not by the agent | Classification is path-based; a risky change inside a "LOW" path (e.g. weakening validation) is not recognised |
| O3 | Broken change reaches the codebase | Med / High | Exit gates (`structure`, `mvn compile`, `mvn test`) after apply; snapshot rollback on failure | Only as good as the tests; no mutation testing |
| O4 | Retry storm / runaway cost | Low / Med | Per-node retry bound, global attempt budget (enforced inside the retry loop), wall-clock budget, exponential backoff | Budgets are static defaults |
| O5 | Cascading failure | Med / High | First unrecoverable failure trips **safe-stop**; downstream `SKIPPED`; state persisted; `resume` | In-flight siblings finish their current attempt (may complete useful work, but also consume time) |
| O6 | Stale downstream work after an upstream change | Med / Med | Data-flow invalidation + newest-first rollback + forced re-approval of changed inputs | Dependencies must be declared (`consumes`); an undeclared dependency is invisible to re-planning |
| O7 | Approval fatigue / rubber-stamping | High / High | Approval is bound to artifact versions and file hashes; request shows the changed paths and reason; fail-closed prompt | `--approve auto` exists for demos – it is explicitly named in the audit trail but must never be used for real changes |
| O8 | Audit log tampering | Low / High | Hash chain; `verify-audit`; release agent checks integrity | **Tamper-evident, not tamper-proof**: someone with write access can rebuild the whole chain. Needs external anchoring of the head hash |
| O9 | Parallel nodes write the same file | Med / Med | Shipped scenarios assign disjoint file sets; every node has its own snapshot | **No overlap detection or locking** – a mis-designed scenario can race (last writer wins) |
| O10 | Crash mid-node | Low / Med | Run state persisted after each node; `RUNNING` → `PENDING` on resume; committed snapshots persisted | Pending (in-flight) snapshots are in memory: files applied by a node that was killed mid-flight are not rolled back on resume; they are overwritten if the node re-runs, but stray files could remain |
| O11 | Human approver identity | Med / Med | Approver recorded in audit (`human:<name>`) | Console identity is `user.name`; no authentication, no separation of duties, no quorum |
| O12 | Shell-out to Maven | Low / Med | Fixed argument vector, no user-controlled strings, timeout, output captured to log | Runs build plugins of the generated project with the user's privileges – needs sandboxing for untrusted agents |

## B. Risk register – the generated URL shortener

| ID | Risk | Mitigation | Residual / follow-up |
|---|---|---|---|
| S1 | **SSRF** via destination URL | Scheme allow-list; credentials rejected; loopback/private/link-local/CGNAT/multicast/ULA; IPv4-mapped IPv6; decimal/hex/short IPs and single-label hosts rejected | **No DNS resolution**: a hostname that *resolves* to an internal address, or DNS rebinding, is not caught. The service never fetches URLs itself, so impact is limited to redirecting users; a fetching feature (link preview) would need resolve-and-pin |
| S2 | Open redirector abused for phishing | Rate limiting on create; admin takedown (soft delete → 410); optional denylist (v3) | No reputation feed / malware scanning; the denylist is a static, configured list |
| S3 | Code collision / exhaustion | 62^7 random space, DB unique constraint, 5 bounded attempts, fail closed with 503 | Random codes need monitoring of the collision rate at scale |
| S4 | Rate limiter bypass | Token bucket per client address | **Per instance** (not global); `X-Forwarded-For` deliberately not trusted, so behind a proxy all clients share one bucket until configured; memory purge heuristic only above 10k keys |
| S5 | Unauthorised delete | `X-API-Key`, constant-time compare, disabled when no key configured | Single shared static key, no per-user identity/rotation. Stats and metadata endpoints are unauthenticated |
| S6 | Synchronous click write on the redirect path | Failure to record never fails the redirect | Adds a DB write per redirect; unbounded table growth; no retention/aggregation |
| S7 | Schema migration on a live table | Additive nullable column, idempotent `IF NOT EXISTS`, backward-compatible API | `schema.sql` at startup is not a migration framework (no versioning/rollback) – adopt Flyway/Liquibase before production |
| S8 | Cache staleness (v3) | TTL 60 s, evict on local delete, expiry re-checked on every hit | Other instances can serve a deleted link for up to the TTL; crude size bound (`clear()` at capacity) rather than LRU |
| S9 | Per-day analytics use server-local dates | – | Document or switch to UTC before multi-region use |
| S10 | IDN hostnames | Rejected (Java `URI` cannot parse them as hosts) – fail closed | Users must submit punycode |
| S11 | H2 file database | Fine for the prototype | Single node, no HA; the `UrlRepository` / `ClickRepository` ports make Postgres a drop-in |
| S12 | **Known seeded defect in v1** | The v1 `UrlValidator` compares reserved aliases case-sensitively (`Admin` bypasses `admin`) and `UrlService` accepts aliases differing only by case. **This was left in on purpose** as the real bug for the brownfield scenario to find and fix | The committed `shortener-service/` is the *v1.0.0 greenfield output and contains this defect*; v1.1.0 (`workspace/demo/v2`, `v3`) fixes it. Do not deploy v1 |

## C. Failure scenarios and how the system responds

| Scenario | Detection | Response | Verified by |
|---|---|---|---|
| Agent throws | exception in attempt | audit `ATTEMPT_FAILED`, backoff, retry, then fallback | `transientFailuresAreRetried…`, `fallbackAgentTakesOver…` |
| Agent emits malformed code | `structure` gate | rollback → retry | `injectedBadOutputIsCaught…`, chaos demo 1 (real run) |
| Compiles but breaks behaviour | `mvn test` gate at join | node fails (rollback of that node), retries, then safe-stop | first greenfield run (see summary) |
| Secret/exec/SQL-concat in output | policy | block, no retry, halt | `policyViolationIsFatal…`, chaos demo 3 |
| Human says no | approval gate | halt before any change | chaos demo 4, `humanGateBlocksUntilApproved…` |
| Primary and fallback fail | retries exhausted | `FAILED` → safe-stop → `SKIPPED` downstream → `resume` later | chaos demo 2, `haltedRunCanBeResumed…` |
| Requirement changes mid-run | clarification event | re-plan: rollback + selective re-run + re-approval | ambiguous scenario, `clarificationReplansOnly…` |
| Operator wants to stop now | `STOP` file | no further scheduling, state kept | `killSwitchFilePreventsAnyNodeFromStarting` |
| History altered after the fact | `verify-audit` | exit code 3, release agent reports NO-GO | `AuditLogTest`, `cliRunsAScenarioAndVerifiesItsAudit` |

## D. Trade-offs

| Choice | Gain | Cost |
|---|---|---|
| **Deterministic rule/template agents instead of an LLM** | Reproducible runs, offline, testable governance, no secrets/cost | Not real code synthesis: output is limited to the template library, requirement understanding is keyword-based. The `Agent` interface is the swap point; an LLM agent would additionally need prompt-injection hardening (policy already treats agent output as untrusted) |
| Engine-side governance | Single enforcement point; agents swappable | The engine is a trusted component and a single point of failure |
| Data-flow re-planning from declared `consumes` | Minimal re-work | Requires disciplined scenario authoring |
| Threads in one JVM | Simple, proves semantics | No horizontal scale or isolation |
| File snapshots | VCS-independent, supports undoing completed nodes | Text files only; not a substitute for git history in real projects |
| Real `mvn` in the loop | Truthful gates | ~5 s per compile, ~15 s per test run; needs Maven and network on first run |
| Hash-chained JSONL | No infrastructure | Not tamper-proof; linear verification |
| `--approve scripted` default | CI/demo friendly | Scenario authors must be trusted not to pre-approve risky work; unknown nodes default to *deny* |

## E. Assumptions

1. The requirement text is trusted input from the user; agent *outputs* are untrusted and always policy-checked.
2. Target projects are Maven/Java text trees; changes are text files below 200 kB.
3. One orchestrator process per run directory; one human approver at a time (approval prompts are serialised).
4. Scenario authors declare `consumes`/`produces` accurately and keep parallel nodes' file sets disjoint.
5. Ambiguity detection covers two quality terms ("safer", "faster") with documented defaults; other vague wording is
   not detected.
6. Maven, a JDK 17+ and network access to Maven Central are available when build gates are enabled.

## F. Known limitations (not done)

* No real LLM agent; no learning from past runs; requirement parsing is keyword-based.
* No authentication of approvers, no multi-party approval, no ticketing/chat integration.
* No external anchoring of the audit chain; no log shipping; no metrics export (Prometheus/OpenTelemetry).
* Single process; no distributed workers, leases, or run-level concurrency control.
* No file-level locking or overlap detection for parallel nodes; no merge of concurrent human edits to the target.
* Pending (in-flight) snapshots are not persisted, see O10.
* Replanning supports one event type (`clarification`) and only applies events while no node is running.
* The generated service lacks authN/Z beyond the admin key, Flyway migrations, observability beyond `/actuator/health`,
  and containerisation.
* CI workflow is untested here.
