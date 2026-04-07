# Handover — 2026-04-07

**Head commit:** `2e58487` — docs: session wrap 2026-04-07 — Flow economics integration
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

- **FlowEconomicsTask** — second R&D integration complete. Quarkus Flow (`quarkus-flow:0.7.1`) via `@Incoming` + `startInstance()` bridge (not `listen` — see GE-0061). Per-tick instances, four `consume` steps: supply, probes, gas, expansion.
- **Assimilator ownership** transferred from `DroolsStrategyTask` to `FlowEconomicsTask` (economic decision, not strategic).
- **128 tests** — up from 107. `EconomicsDecisionServiceTest` (16 plain JUnit) + `EconomicsFlowTest` (4 `@QuarkusTest`).
- **ADR-0001** — documents three Flow placement options (A: per-tick plugin ✓, B: stateful multi-tick, C: match lifecycle). Option A chosen; all three on record for revisit.
- **Garden** — GE-0059 (consume step mutation loss), GE-0060 (POJO FAIL_ON_EMPTY_BEANS), GE-0061 (listen silent with SmallRye), GE-0062 (@Incoming+startInstance bridge).
- **CLAUDE.md** — added `plugin/flow/` to code org, Flow test pattern note, ADR section.
- **Issue tracking** — enabled (mdproctor/starcraft). All future commits should reference an issue (`Refs #N`).

## Key Technical Findings (quarkus-flow 0.7.1)

- `listen(name, toOne(type))` only works with CloudEvent channels — silent with SmallRye in-memory
- Bridge: `@Incoming("channel") Uni<Void> method(T t) { return startInstance(t).replaceWithVoid(); }`
- `consume` steps re-deserialize from JSON — mutable input mutations don't propagate across steps
- Plain mutable POJOs as workflow input → `FAIL_ON_EMPTY_BEANS` — use records or `@JsonAutoDetect`

## Immediate Next Step

**TacticsTask** — third R&D integration target. Options: gdx-ai behaviour trees (JVM-only, `com.badlogicgames.gdx:gdx-ai:1.8.2`) or GOAP planner (~300 LOC). See `docs/library-research.md` §2.2 (gdx-ai) and §2.3 (GOAP). **Create a GitHub issue before starting implementation** (CLAUDE.md work tracking rules).

## Open Questions / Blockers

*Unchanged — `git show HEAD~1:HANDOVER.md`* (SC2Engine.tick() ownership, ReplayEngine profile, 7 unparseable AI Arena replays, Assimilator placement, Phase 1 Task 9)

**New:** Flow per-tick instance overhead — needs profiling against real SC2 at 500ms/tick. Budget arbitration across Flow `consume` steps is silently broken (each step sees original budget); SC2 rejects unaffordable commands so no game breakage, but the design assumption was wrong.

## References

| Context | Where |
|---|---|
| Design snapshot | `docs/design-snapshots/2026-04-07-flow-economics-integration.md` |
| ADR-0001 | `docs/adr/0001-quarkus-flow-placement.md` |
| Spec | `docs/superpowers/specs/2026-04-07-flow-economics-design.md` |
| Plan | `docs/superpowers/plans/2026-04-07-flow-economics-task.md` |
| Blog | `docs/blog/2026-04-07-mdp03-flow-economics-arrives.md` |
| Garden | `~/claude/knowledge-garden/GARDEN.md` (index only) |
| Library research | `docs/library-research.md` |

## Environment

*Unchanged — `git show HEAD~1:HANDOVER.md`*
