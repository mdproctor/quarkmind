# QuarkMind — Design Snapshot
**Date:** 2026-04-09
**Topic:** Emulation Engine E3 (combat) complete + #15 budget fix
**Supersedes:** [2026-04-09-emulation-e1-visualizer-complete](2026-04-09-emulation-e1-visualizer-complete.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

QuarkMind's emulation engine now supports full combat: units have shields and
HP, deal damage each tick via two-pass simultaneous resolution, and are removed
when HP reaches zero. Visualiser sprites tint yellow→red as health drops and
disappear on death. The economics plugin's budget overcommit bug (#15) is fixed
— Quarkus Flow no longer re-deserialises the budget between decision steps. 236
tests pass across unit, integration, and Playwright E2E layers. E4 (enemy active
AI) and E5 (pathfinding) are next.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Flat damage per tick (E3) | `SC2Data.damagePerTick(UnitType)` | At 500ms/tick, per-attack events are visually indistinguishable; simpler to test | Attack cooldowns — deferred to E4 |
| Two-pass simultaneous combat | Collect damage into Map, then apply | Prevents order-dependency (unit A kills B before B can counterattack) | Sequential processing |
| Shields in domain model | `Unit` record gains `shields`/`maxShields` | Protoss flavour; avoids future domain model change; compiler catches all call sites | Shields as EmulatedGame-only parallel state |
| `attackingUnits` Set (not `unitTargets`) | Separate set populated only by `AttackIntent` | SC2 semantic: move-only commands don't auto-attack | Gate on `unitTargets` (includes MoveIntent — wrong semantics) |
| Collapse Flow steps into `checkAll()` | Single `consume()` step calls all four decisions sequentially | Quarkus Flow serialises between `consume()` steps — separate steps reset the shared `ResourceBudget` | Four-step approach (broken by design) |
| Health tinting on Container + inner Sprite | Write `tintVal` to both objects | PixiJS 8 Container tint propagates but inner Sprite tint from `makeUnitSprite()` is a separate locus | Container only (confusing dual-locus, not guaranteed to render) |

## Where We're Going

**Next steps:**
- E4: Enemy active AI — enemy economy + production + attack waves
- E5: Pathfinding + terrain — A* on tile map, units navigate obstacles
- Post-E3 benchmark — run `mvn test -Pbenchmark` and record in `docs/benchmarks/`
- Visualiser deferred: probe overlap fix, HTML mineral display, geyser sprite, time-based UI tests

**Open questions:**
- `attackingUnits` is never cleared by a `MoveIntent` — a unit given a retreat command continues firing; E4 should add an explicit cancel path
- `ReplaySimulatedGame` uses `shields=0` for replay units — replay tracker events don't include instantaneous shield state
- Observer supply cost defaults to 2 in `SC2Data.supplyCost` (real SC2 value is 1) — minor data gap, no test coverage for Observer training
- Scouting CEP thresholds (#16) still need calibration against replay data
- `attackingUnits` set is never cleared except on unit death — semantics evolve in E4 when retreat becomes a deliberate combat action

## Linked ADRs

*(No ADRs created yet — strong candidates: two-pass simultaneous combat resolution, `attackingUnits` vs `unitTargets` semantics, Quarkus Flow single-step pattern for shared mutable budget state.)*

## Context Links

- E3 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e3-design.md`
- E2 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md`
- Visualiser spec: `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md`
- Benchmark baseline: `docs/benchmarks/2026-04-09-pre-e2-baseline.md`
- GitHub issues: #13 (smoke test), #14 (GraalVM), #15 (closed — budget fix), #16 (CEP thresholds)
- Previous snapshot: `docs/design-snapshots/2026-04-09-emulation-e1-visualizer-complete.md`
