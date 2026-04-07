# Handover — 2026-04-07

**Head commit:** `0ba64b0` — docs: add blog entry 2026-04-07-02 — drools strategy arrives
**Previous handover:** `git show HEAD~3:HANDOVER.md` | diff: `git diff HEAD~3 HEAD -- HANDOVER.md`

## What Changed This Session

- **DroolsStrategyTask** — first real R&D integration; replaces `BasicStrategyTask` as the active CDI plugin. Drools 10.1.0 Rule Units (AOT via Executable Model). Rules are declarative (build order conditions, strategy posture); budget enforcement and intent dispatch stay in Java.
- **StarCraftTaskRegistrar** — fixed a silent CaseHub integration gap: all `@CaseType` plugin beans were being removed by Quarkus Arc's dead-bean elimination (nothing was injecting them via CDI). Registry was always empty; plugins had never actually run through CaseHub. Startup registrar injects each seam interface and registers with `TaskDefinitionRegistry`.
- **StrategyRuleUnit + StarCraftStrategy.drl** — `RuleUnitData` with `DataStore<T>` input facts and `List<String>` decision output. No application-typed plain fields (would cause `ClassNotFoundException` in `SimpleRuleUnitVariable` static init — see GE-0053, GE-0056).
- **BasicStrategyTask** — demoted to plain (non-CDI) class; retained for direct-instantiation tests and as reference implementation.
- **DroolsStrategyTaskTest** — 13 new `@QuarkusTest` tests (`DataSource.createStore()` requires Quarkus context in `drools-quarkus`). `@BeforeEach @AfterEach` drains `IntentQueue` to prevent `@ApplicationScoped` state bleed between test classes (GE-0036).
- **107 tests** — up from 94.
- **Garden** — GE-0053 (DataSource.createStore() NPE in plain JUnit), GE-0056 (DRL OOPath: empty brackets, `type` keyword, `isComplete` JavaBean convention).
- **Blog** — `docs/blog/2026-04-07-02-drools-strategy-arrives.md`.

## State Right Now

- `mvn test` passes 107 tests
- Drools is the active `StrategyTask` — rules fire declaratively each CaseHub tick
- CaseHub integration is now real: all four plugin seams execute through `TaskDefinitionRegistry`
- Three run modes working: mock (auto-start), replay, sc2

## Immediate Next Step

**Quarkus Flow `EconomicsTask`** — replace `BasicEconomicsTask` with a Quarkus Flow workflow implementation. This is the second R&D integration target. See `docs/library-research.md` §4.4 for the Quarkus Flow evaluation. Add `quarkus-flow` dependency and implement a `FlowEconomicsTask implements EconomicsTask`.

## Open Questions / Blockers

- `SC2Engine.tick()` ownership — mock owns clock, real SC2 doesn't; could unify under scheduler
- `ReplayEngine` profile vs config-driven — profile is simpler; config allows runtime switching
- 7 AI Arena replays unparseable (build > 81009) — await new `.dat` files from Blizzard
- Assimilator placement in real SC2 needs geyser detection (neutral unit scan in `ObservationTranslator`)
- Phase 1 Task 9 (live SC2 smoke test) — blocked on SC2 availability
- Arc silent bean removal: three more gotchas worth garden entries (GE-0054+ pending) — see session wrap garden sweep notes

## DRL Gotchas (quick reference for next Drools work)

- No-constraint pattern: `/store` not `/store[]` (empty brackets = parse error)
- `type` is a DRL keyword: always `this.type()` in constraints
- `isComplete` maps to property `complete` via JavaBean convention: always `this.isComplete()`
- Non-DataStore fields with application types in `RuleUnitData` → `ClassNotFoundException` in static init of generated bean. Use `DataStore<T>` (erases to `DataStore`) or `List<String>` decisions.
- Drools tests always need `@QuarkusTest` — `DataSource.createStore()` factory is null without Quarkus boot.

## References

| Context | Where | Retrieve with |
|---|---|---|
| Design state | `docs/DESIGN.md` | `cat` |
| Previous design snapshot | `docs/design-snapshots/2026-04-07-full-agent-loop-complete.md` | `cat` |
| Engine roadmap | `docs/roadmap-sc2-engine.md` | `cat` |
| Plugin guide | `docs/plugin-guide.md` | `cat` |
| Library research (Quarkus Flow §4.4) | `docs/library-research.md` | `cat` |
| Replay index | `replays/replay-index.md` | `cat` |
| Garden gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only |
| Blog | `docs/blog/2026-04-07-02-drools-strategy-arrives.md` | `cat` |

## Environment

*Unchanged — `git show HEAD~3:HANDOVER.md`*
