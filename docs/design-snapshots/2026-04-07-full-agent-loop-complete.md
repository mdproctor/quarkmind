# StarCraft II Quarkus Agent — Design Snapshot
**Date:** 2026-04-07
**Topic:** Full agent loop complete — all four plugins, engine abstraction, replay infrastructure
**Supersedes:** [2026-04-06-phase0-phase1-complete](2026-04-06-phase0-phase1-complete.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

A fully operational Quarkus SC2 agent with four working plugins (Economics, Strategy, Scouting, Tactics), a clean `SC2Engine` abstraction covering mock/replay/real backends, realistic build-time simulation, per-tick resource arbitration, and active probe scouting. Three run modes all work end-to-end: mock (auto-start, hand-crafted simulation), replay (observe-only against real `.SC2Replay` files), and real SC2. 94 tests pass. End-user documentation (README, plugin-guide, running.md) is in place.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Engine abstraction | Single `SC2Engine` seam replacing 3 interfaces | All three always move together; one injection point cleaner | Keep 3 separate seams |
| Replay mode | `ReplayEngine` observe-only; `dispatch()` records intents | Replay is immutable; intents logged for offline evaluation | Apply intents to a shadow simulation |
| Resource arbitration | `ResourceBudget` in CaseFile, consumed by plugins | Prevents double-spend without inter-plugin communication | Check raw minerals; accept occasional over-commit |
| Build times | `PendingCompletion` with `completesAtTick`; buildings appear as `isComplete=false` immediately | Plugins need to see under-construction buildings; supply granted only on completion | 1-tick instant (previous) or per-building queues |
| Active scouting | `@ApplicationScoped` state tracking scout probe tag | Singleton CDI bean state persists across ticks; simple state machine | CaseFile key per tick (doesn't persist) |
| Mock auto-start | `MockStartupBean` with `@UnlessBuildProfile(anyOf = {"sc2","replay","test","prod"})` | Mirrors SC2StartupBean/ReplayStartupBean pattern; `anyOf` undocumented but works | Require manual POST /sc2/start |
| Geyser positions | Hardcoded 2 geysers in `SimulatedGame.reset()` | Sufficient for mock Assimilator testing; real positions deferred | Track from replay UnitBorn events |
| Enemy base estimate | Opposite-quadrant heuristic (x<64 → 224, else 32) | Good enough for initial scout dispatch on symmetric maps | Map data (not yet available) |

## Where We're Going

**Next steps:**
- **Drools `StrategyTask`** — first real R&D integration; replace `BasicStrategyTask` with a Drools rule engine
- **Quarkus Flow `EconomicsTask`** — model economic decisions as a reactive flow
- **Phase 4: `HttpSC2Engine`** — network bridge; SC2 on one machine, agent on another
- **Mineral collection model** — replace flat +5/tick trickle with a worker-saturation curve
- **Unit movement in mock** — `AttackIntent`/`MoveIntent` currently no-ops; units don't move
- **Phase 1 Task 9** — live SC2 smoke test (blocked on SC2 availability)

**Open questions:**
- Which R&D integration comes first: Drools `StrategyTask` or Quarkus Flow `EconomicsTask`?
- Should `SC2Engine.tick()` be removed in favour of always-scheduler-driven timing? Currently split: mock owns clock, real SC2 doesn't.
- `ReplayEngine` profile vs config-driven (`starcraft.engine=replay`)? Profile is simple; config allows runtime switching.
- 7 AI Arena replays unparseable (build > 81009) — add new `.dat` files when available from Blizzard.
- Assimilator placement in real SC2 needs geyser detection (neutral unit scan in `ObservationTranslator`).

## Linked ADRs

*(No ADRs created yet — all decisions captured in snapshots and design spec)*

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-06-starcraft-agent-design.md`
- Engine roadmap: `docs/roadmap-sc2-engine.md`
- Library research: `docs/library-research.md`
- Replay index: `replays/replay-index.md`
- Previous snapshot: `docs/design-snapshots/2026-04-06-phase0-phase1-complete.md`
- Scelight fork: `/Users/mdproctor/claude/scelight` branch `feature/standalone-modules`
- AI Arena token: `a7d5c711347a865f8855d257649a1ae71f7ae4b6`
