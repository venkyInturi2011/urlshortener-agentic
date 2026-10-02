# Run report: brownfield

**Status:** HALTED (node design_review failed: approval denied by venky)

> Add link expiry (optional expiresAt on create, redirect returns 410 after expiry) and per-day click analytics on the stats endpoint. Fix bug: the reserved-word check for custom aliases can be bypassed by changing the case, and aliases that differ only by case are accepted.

**Audit chain:** VALID - 44 records, chain intact

## Workflow graph

```mermaid
flowchart TD
  requirements["requirements"]
  impact_analysis["impact_analysis"]
  architecture["architecture"]
  risk_analysis["risk_analysis"]
  design_review["design_review<br/>HUMAN GATE"]
  impl_persistence["impl_persistence"]
  impl_api_contract["impl_api_contract"]
  impl_service_logic["impl_service_logic"]
  update_tests["update_tests"]
  verify["verify"]
  docs["docs"]
  release_readiness["release_readiness<br/>HUMAN GATE"]
  requirements --> impact_analysis
  requirements --> architecture
  requirements --> risk_analysis
  impact_analysis --> design_review
  architecture --> design_review
  risk_analysis --> design_review
  design_review --> impl_persistence
  design_review --> impl_api_contract
  impl_persistence --> impl_service_logic
  impl_api_contract --> impl_service_logic
  impl_service_logic --> update_tests
  update_tests --> verify
  verify --> docs
  docs --> release_readiness
```

Parallel layers: [[requirements], [impact_analysis, architecture, risk_analysis], [design_review], [impl_persistence, impl_api_contract], [impl_service_logic], [update_tests], [verify], [docs], [release_readiness]]  
Join (sync) nodes: [design_review, impl_service_logic]

## Nodes

| Node | Agent | Status | Attempts | Fallback | Rollbacks | Runs |
|---|---|---|---|---|---|---|
| requirements | requirements | DONE | 1 | false | 0 | 1 |
| impact_analysis | impact-analyst | DONE | 1 | false | 0 | 1 |
| architecture | architect | DONE | 1 | false | 0 | 1 |
| risk_analysis | risk | DONE | 1 | false | 0 | 1 |
| design_review (gate) | reviewer | FAILED | 1 | false | 0 | 0 |
| impl_persistence | implementer | SKIPPED | 0 | false | 0 | 0 |
| impl_api_contract | implementer | SKIPPED | 0 | false | 0 | 0 |
| impl_service_logic | implementer | SKIPPED | 0 | false | 0 | 0 |
| update_tests | tester | SKIPPED | 0 | false | 0 | 0 |
| verify | reviewer | SKIPPED | 0 | false | 0 | 0 |
| docs | documenter | SKIPPED | 0 | false | 0 | 0 |
| release_readiness (gate) | release-manager | SKIPPED | 0 | false | 0 | 0 |

## Reliability metrics

| Metric | Value |
|---|---|
| nodes_total | 12 |
| nodes_done | 4 |
| node_executions_completed | 4 |
| node_success_rate | 0.333 |
| first_attempt_success_rate | 1.0 |
| attempts | 5 |
| failed_attempts | 1 |
| retries | 0 |
| fallbacks | 0 |
| rollbacks | 0 |
| policy_blocks | 0 |
| approvals_requested | 1 |
| approvals_denied | 1 |
| approval_wait_ms | 0 |
| replans | 0 |
| mttr_ms | null |
| recovered_incidents | 0 |
| unrecovered_incidents | 1 |
| e2e_latency_ms | 210 |

## Decisions

- **approval-denied** [design_review] by venky: Approve additive API contract change (expiresAt, clicksByDay) and schema migration
- **safe-stop** by orchestrator: node design_review failed: approval denied by venky

## Decision lineage (inputs to outputs)

| Node | Run | Agent | Consumed | Produced |
|---|---|---|---|---|
| requirements | 1 | requirements | {} | {req.functional=v1, req.security=v1, req.performance=v1, req.ambiguities=v1} |
| risk_analysis | 1 | risk | {req.functional=1, req.ambiguities=1} | {design.risks=v1} |
| architecture | 1 | architect | {req.functional=1, req.security=1, req.performance=1} | {design.architecture=v1, design.openapi=v1} |
| impact_analysis | 1 | impact-analyst | {req.functional=1} | {impact.report=v1, impact.summary=v1} |

## Artifact versions

| Artifact | Version | Produced by | SHA-256 |
|---|---|---|---|
| req.functional | v1 | requirements | eca8f88ad98a |
| req.security | v1 | requirements | e6a2a98ba81f |
| req.performance | v1 | requirements | f461ced770e3 |
| req.ambiguities | v1 | requirements | 27f34907456f |
| design.risks | v1 | risk_analysis | 8b1acb740e24 |
| design.architecture | v1 | architecture | 30c33610d1d0 |
| design.openapi | v1 | architecture | e0b033f9c775 |
| impact.report | v1 | impact_analysis | eeeb5812d0a5 |
| impact.summary | v1 | impact_analysis | eae16f291823 |
