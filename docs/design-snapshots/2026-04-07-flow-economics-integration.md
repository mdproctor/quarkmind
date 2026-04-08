# Quarkus Flow EconomicsTask — Design Snapshot
**Date:** 2026-04-07
**Topic:** Second R&D integration — Quarkus Flow as the EconomicsTask plugin
**Supersedes:** *(none)*
**Superseded by:** [2026-04-08-drools-goap-tactics-integration](2026-04-08-drools-goap-tactics-integration.md)

---

## Where We Are

`FlowEconomicsTask` is the active `EconomicsTask` CaseHub plugin, backed by
`EconomicsFlow` — a Quarkus Flow `Flow` subclass. Each CaseHub tick,
`FlowEconomicsTask.execute()` emits a `GameStateTick` on a SmallRye in-memory
channel. `EconomicsFlow` receives it via `@Incoming` and starts a per-tick
workflow instance that runs four `consume` steps: supply check (pylon),
probe training, assimilator building (transferred from Drools), and expansion
signalling. 128 tests pass. Drools owns strategy; Flow owns economics.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Flow placement | Stateful multi-tick within plugin seam (ADR-0001 Option A) | Exercises Flow's stateful workflow model; Drools/Flow signal boundary is clean | Per-tick blocking (Option B: Drools does this better), match lifecycle only (Option C: defers the R&D question) |
| Flow integration with SmallRye | `@Incoming` bridge + `startInstance(tick)` | `listen` task only accepts CloudEvents; in-memory channel carries plain POJOs | `listen` task (silent — never fires, GE-0061) |
| Assimilator ownership | Transferred from DroolsStrategyTask to FlowEconomicsTask | Assimilator is an economic decision; separation keeps Drools strategic | Leaving it in Drools (conflated economics with strategy) |
| Workflow input type | Immutable `GameStateTick` record | Jackson cannot serialize plain mutable classes (GE-0060); records work natively | Mutable POJO (FAIL_ON_EMPTY_BEANS at runtime) |
| Budget snapshot | Copy minerals/vespene into fresh `ResourceBudget` at emit time | Flow processes one tick later; original budget already spent by other plugins | Passing shared budget reference (would spend stale budget) |
| Per-tick vs long-lived | Per-tick Flow instances | `listen` CloudEvent subscription not available for SmallRye in-memory; per-tick is functionally correct for current scope | Long-lived stateful instance (spec intent — revisit when CEP / CloudEvent infra lands) |

## Where We're Going

**Next steps:**
- `TacticsTask` — third R&D integration target (gdx-ai behaviour trees or GOAP planner)
- Drools CEP (`@role(event)`, temporal operators) — once added, writes CaseFile signals (`SIGNAL_GAS_READY`, `SIGNAL_SATURATED`) that `FlowEconomicsTask` reads; flow condition checks become reads of those keys with no change to the flow itself
- Match lifecycle Flow (ADR-0001 Option C) — separate `FlowMatchLifecycle` for game start → end → analysis; complements the in-tick economics flow
- Flow instance cancellation on `GameStopped` — current `EconomicsLifecycle.onGameStop()` only logs; proper cancellation deferred until the quarkus-flow cancellation API is validated

**Open questions:**
- Does per-tick `startInstance` create unacceptable overhead at high tick rates? Needs profiling against real SC2 at 500ms/tick
- When Drools CEP lands, will the signal → Flow boundary be as clean as designed, or will timing issues emerge between the synchronous CaseHub tick and async flow processing?
- Should `ResourceBudget` mutations across Flow `consume` steps ever be coordinated? Or is "each step sees original budget" correct given SC2 rejects unaffordable commands anyway?
- Can the `listen` + CloudEvent path be used for match lifecycle events (game start/end) even if not for per-tick economics?

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001 — Quarkus Flow placement](../adr/0001-quarkus-flow-placement.md) | Where Flow lives relative to CaseHub: per-tick stateful plugin (Option A), with Options B/C documented for revisit |

## Context Links

- Spec: `docs/superpowers/specs/2026-04-07-flow-economics-design.md`
- Plan: `docs/superpowers/plans/2026-04-07-flow-economics-task.md`
- Garden entries: GE-0059 (consume step mutation loss), GE-0060 (POJO FAIL_ON_EMPTY_BEANS), GE-0061 (listen task silent with SmallRye), GE-0062 (@Incoming+startInstance bridge pattern)
