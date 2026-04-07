# Handover — 2026-04-07

**Head commit:** `bd99c5e` — docs: add blog entry 2026-04-07-01 — no more stubs, full agent loop
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

- **SC2Engine abstraction** — merged `SC2Client` + `GameObserver` + `CommandDispatcher` into one CDI seam; `MockEngine` and `RealSC2Engine` replace 6 separate beans; `AgentOrchestrator` has 1 injection instead of 4
- **ReplayEngine (Phase 3)** — `%replay` profile; auto-starts via `ReplayStartupBean`; `dispatch()` records intents without applying them; endpoints fully functional after fixing NPE (null game before connect) and UNKNOWN unit filtering
- **All four Basic plugins** — `BasicEconomicsTask`, `BasicStrategyTask` (gateway opener + assimilator + Stalkers), `BasicScoutingTask` (passive intel + active probe dispatch), `BasicTacticsTask` (reads STRATEGY key, AttackIntent/MoveIntent)
- **ResourceBudget** — per-tick mutable budget in CaseFile; prevents cross-plugin double-spend
- **Realistic build times** — `PendingCompletion` records with `completesAtTick`; buildings visible as `isComplete=false` during construction
- **MockStartupBean** — mock mode auto-starts; `mvn quarkus:dev` no longer needs `POST /sc2/start`
- **GameState.geysers** — vespene geyser positions added to domain; 2 hardcoded in SimulatedGame; Assimilator can be built
- **Active scouting** — `@ApplicationScoped` volatile probe tag tracks scout across ticks
- **Docs** — README, `docs/plugin-guide.md`, `docs/running.md` created
- **GE-0041** (previous session) + **GE-0052** submitted to knowledge garden — Scelight tracker traps; `@UnlessBuildProfile(anyOf=...)` undocumented attribute
- **94 tests** (was 38 at session start)

## State Right Now

- `mvn test` passes 94 tests
- All four plugin seams have real implementations; no pass-through stubs remain
- Three run modes working: `mvn quarkus:dev` (mock, auto-start), `mvn quarkus:dev -Dquarkus.profile=replay`, `mvn quarkus:dev -Dquarkus.profile=sc2`
- `DESIGN.md` reflects current architecture; design snapshot frozen at `docs/design-snapshots/2026-04-07-full-agent-loop-complete.md`

## Immediate Next Step

**Drools `StrategyTask`** — replace `BasicStrategyTask` with a Drools rule engine implementation. This is the first real R&D integration, the stated purpose of the project. Start by reading `docs/library-research.md` for the Drools dependency evaluation, then add `drools-quarkus-ruleunits` to `pom.xml` and implement a `DroolsStrategyTask implements StrategyTask`.

## Open Questions / Blockers

- Which R&D integration first: Drools `StrategyTask` or Quarkus Flow `EconomicsTask`? (decided: Drools first)
- `SC2Engine.tick()` ownership — mock owns clock, real SC2 doesn't; could unify under scheduler
- `ReplayEngine` profile vs config-driven — profile is simpler; config allows runtime switching
- 7 AI Arena replays unparseable (build > 81009) — await new `.dat` files from Blizzard
- Assimilator placement in real SC2 needs geyser detection (neutral unit scan in `ObservationTranslator`)
- Phase 1 Task 9 (live SC2 smoke test) — blocked on SC2 availability

## References

| Context | Where | Retrieve with |
|---|---|---|
| Design state | `docs/design-snapshots/2026-04-07-full-agent-loop-complete.md` | `cat` |
| Engine roadmap | `docs/roadmap-sc2-engine.md` | `cat` |
| Plugin guide | `docs/plugin-guide.md` | `cat` |
| Library research | `docs/library-research.md` | `cat` |
| Replay index | `replays/replay-index.md` | `cat` |
| Garden gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only |
| Scelight fork | `/Users/mdproctor/claude/scelight` branch `feature/standalone-modules` | `git log --oneline` |

## Environment

- AI Arena token: `a7d5c711347a865f8855d257649a1ae71f7ae4b6` (for future replay downloads)
- CaseHub: `cd /Users/mdproctor/claude/alpha && mvn install -DskipTests` on fresh machine
- Scelight replay libs: `cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh`
