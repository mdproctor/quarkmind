# StarCraft Agent — Design Snapshot
**Date:** 2026-04-08
**Topic:** Drools GOAP TacticsTask — third R&D plugin integration
**Supersedes:** [2026-04-07-flow-economics-integration](2026-04-07-flow-economics-integration.md)
**Superseded by:** *(leave blank)*

---

## Where We Are

Three of four plugin seams are now backed by real R&D implementations:
`DroolsStrategyTask` (Drools rules), `FlowEconomicsTask` (Quarkus Flow),
`DroolsTacticsTask` (Drools GOAP planner + Java A*). The fourth, `ScoutingTask`,
remains a stub. 152 tests pass. GitHub issue tracking is active (`mdproctor/starcraft`).

`DroolsTacticsTask` classifies army units into groups (low-health / in-range /
out-of-range) via Drools rules each tick, emits an action library via a second
rule phase, then runs Java A* per group to find the cheapest action sequence.
The first action in each plan is dispatched as an `AttackIntent` or `MoveIntent`.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Tactics library | Hand-rolled GOAP (~300 LOC) | Native-safe, no external dep, Drools already in stack | gdx-ai (JVM-only, no GraalVM metadata) |
| Drools role in GOAP | Action compiler — fires once per tick, produces `GoapAction` list | No session cloning per A* node; one session per tick | Per-node oracle (session cloning); pure forward-chaining planner (greedy, not cost-optimal) |
| Goal assignment | Two-level: strategy → Drools decomposes per group | Strategic intent + individual situation respected; policy in DRL = hot-reloadable | Direct translation (rigid); bottom-up independent (contradicts strategy) |
| Unit grouping | Hybrid — groups by shared tactical state | Balances coordination with per-unit context | Per-unit (N planners/tick); per-army (no individual context) |
| Inter-phase Drools signalling | `DataStore<String> activeGroups` (Phase 1 inserts, Phase 2 matches) | DataStore insertions trigger re-evaluation; plain List mutations don't (GE-0109) | `eval(list.stream()...)` in Phase 2 LHS — silently never fires |
| DEFEND handling | Bypass GOAP, emit MoveIntent directly | Too simple to warrant planning in spike | GOAP `MoveToBase` action (deferred to full action vocabulary) |

## Where We're Going

**Next steps:**
- `ScoutingTask` — fourth and final plugin seam (stub to real implementation)
- Full GOAP action vocabulary: `Kite`, `Focus`, `FlankLeft`, `FlankRight`
- Budget arbitration fix in `FlowEconomicsTask` — each consume step sees original budget (known open issue)
- SC2Engine.tick() ownership, ReplayEngine profile — still open from Phase 1

**Open questions:**
- Flow per-tick instance overhead — needs profiling against real SC2 at 500ms/tick
- Whether GOAP goal assignment policy should be configurable at runtime (DRL hot-reload enables this — not yet exercised)
- TacticsTask range constant (6.0 tiles) — hardcoded for Stalker; how to parameterise for multi-unit-type armies

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001 — Quarkus Flow placement](../adr/0001-quarkus-flow-placement.md) | Per-tick Flow integration pattern |

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-08-drools-goap-tactics-design.md`
- Implementation plan: `docs/superpowers/plans/2026-04-08-drools-goap-tactics-task.md`
- Active epic: [#1 TacticsTask R&D integration](https://github.com/mdproctor/starcraft/issues/1)
- Garden: GE-0105 (Drools action compiler pattern), GE-0109 (DataStore inter-phase signalling)
