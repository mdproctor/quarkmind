# Handover — 2026-04-06

**Head commit:** `3e07252` — docs: add blog entry 2026-04-06-04
**Previous handover:** none (first handover)

## What Changed This Session

- **Phase 0** complete: mock architecture, 27 tests, CaseEngine + 4 dummy plugins, QA REST harness
- **Phase 1** complete (code): `sc2.real` package — RealSC2Client, RealGameObserver, RealCommandDispatcher, SC2DebugScenarioRunner, `%sc2` profile; Task 9 (live SC2 smoke test) pending
- **Scelight extraction**: `feature/standalone-modules` branch at `/Users/mdproctor/claude/scelight` — `scelight-mpq` and `scelight-s2protocol` standalone Maven modules; 10 clean atomic commits; quick-build: `./scripts/publish-replay-libs.sh`
- **Replay infrastructure**: IEM10 Taipei 2016 (30 games, pre-processed JSON) + AI Arena bot replays (22 parseable `.SC2Replay`); index at `replays/replay-index.md`; AI Arena token saved in session memory
- **Bugs fixed this session**: `Protocol.DEFAULT` null (`.dat` files not in JAR), `quarkus:dev` silent skip (missing `build` goal), QA CDI profile injection, Maven aggregator packaging
- **Scelight commit discipline**: atomic commits only — fixes / build changes / redesigns never mixed; memory saved

## State Right Now

- `mvn test` passes (30 tests)
- Replay parser working: `RepParserEngine.parseReplay(path)` on build 75689 replays
- Scelight JAR rebuilt and in `~/.m2` with `.dat` files included
- 4 blog entries written for the day; design snapshot frozen

## Immediate Next Step

**Build `ReplaySimulatedGame`** — a `SimulatedGame` variant that steps through tracker events from a real replay instead of the hand-crafted economic trickle. Start with `Nothing_4720936.SC2Replay` (8m21s PvZ win, consistent bot build order). The tracker events in `replay.trackerEvents.events` give `UnitBornEvent`, `PlayerStatsEvent` etc. per game action — translate these to `SimulatedGame` state mutations.

## Open Questions / Blockers

- Phase 1 Task 9 (live SC2 smoke test) blocked until SC2 installable on this machine
- Mock mode still requires manual `POST /sc2/start` — quick fix: add `SC2StartupBean` equivalent for mock profile
- 7 AI Arena replays unparseable (newer build > 81009) — add new `.dat` files when available from Blizzard

## References

| Context | Where | Retrieve with |
|---|---|---|
| Design state | `docs/design-snapshots/2026-04-06-phase0-phase1-complete.md` | `cat` |
| Design spec | `docs/superpowers/specs/2026-04-06-starcraft-agent-design.md` | `cat` |
| Library research | `docs/library-research.md` | `cat` |
| Replay index | `replays/replay-index.md` | `cat` |
| Phase 1 plan | `docs/superpowers/plans/2026-04-06-phase1-sc2-bootstrap.md` | `cat` |
| Scelight branch | `/Users/mdproctor/claude/scelight` branch `feature/standalone-modules` | `git log --oneline` |
| Garden entries | `~/claude/knowledge-garden/GARDEN.md` | index only |

## Environment

- Scelight: run `cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh` before building if `.dat` fix is needed
- CaseHub: run `cd /Users/mdproctor/claude/alpha && mvn install -DskipTests` on fresh machine
- AI Arena token: `a7d5c711347a865f8855d257649a1ae71f7ae4b6` (use for future replay downloads)
