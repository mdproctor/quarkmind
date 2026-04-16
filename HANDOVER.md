# Handover — 2026-04-16

**Head commit:** `ab78614` — docs: add blog entry 2026-04-16 — fog of war (E9); update CLAUDE.md

## What Changed This Session

**Scouting fix (pre-E9):**
- `DroolsScoutingTask.estimatedEnemyBase()` now takes `int mapWidth` param; `%emulated` overrides to 64 via `scouting.map.width` config — scout dispatches to (56,56) not clamped (63,63)
- 7 unit tests + 1 IT regression guard added; scouting fix closed GitHub #67 (wrong — #67 was E9; scouting had no issue)

**E9: Fog of War — complete (GitHub #66 epic, #67 implementation, closed)**

- `TileVisibility` enum (UNSEEN/MEMORY/VISIBLE) in `sc2/emulated/`
- `VisibilityGrid` — 64×64 tile states, SC2 sight ranges, high-ground rule (LOW/RAMP observers cannot illuminate HIGH tiles), `encode()` → 4096-char string for WebSocket
- `EmulatedGame`: recomputes visibility after `moveFriendlyUnits()` each tick; filters `enemyUnits` + `enemyStagingArea` in `snapshot()` (gated on `terrainGrid != null` for backward compat)
- `VisibilityHolder` — `@ApplicationScoped` CDI bridge, no profile guard; `EmulatedEngine` writes to it each tick
- `GameStateBroadcast(GameState state, String visibility)` record; broadcaster wraps payload — visualizer receives `{state, visibility}` envelope
- `visualizer.js`: fog layer (`PIXI.Graphics`) added between terrain and unit layers; `updateFog()` single-pass draw; `onmessage` unwraps `msg.state` / `msg.visibility`; loop uses `VIEWPORT_H` (32) not 64 (perf fix from code review)
- 409 surefire tests; 2 Playwright fog tests in `VisualizerFogRenderTest`

Executed via subagent-driven development (9 tasks, spec + quality review per task).

**Garden submissions (2 PRs):**
- Hortora/garden#67 — PixiJS Graphics loop over full grid draws invisible off-screen tiles silently
- Hortora/garden#68 — `@ApplicationScoped` CDI bridge bean for profile-specific data flow

**Blog:** `docs/_posts/2026-04-16-mdp03-what-the-probe-sees.md`
**Screenshot needed:** Run emulated mode and capture fog visualizer → `docs/blog/assets/e9-fog-of-war.png`

## Immediate Next Step

**E10 candidates:**
1. **TacticsTask extension** — GOAP retreat to actual base (not MAP_CENTER), kite, focus-fire
2. **Fog of war polish** — capture the missing visualizer screenshot; verify fog works end-to-end in emulated mode (hasn't been manually tested yet this session)

Start next session by running `mvn quarkus:dev -Dquarkus.profile=emulated` and visually verifying the fog, then capture the screenshot for the blog entry.

## Open Questions / Blockers

*Prior open questions unchanged — `git show 62e6f03:HANDOVER.md`*

**New:**
- E9 fog hasn't been manually verified in running emulated mode — automated tests pass but visual check still outstanding
- `VisibilityGrid.recompute(null terrain)` produces selective visibility (only within-range tiles), NOT all-visible; `snapshot()` must gate fog filter on `terrainGrid != null` — documented in code with comment
- ASL/GitHub publication: EmulatedGame is a clean reimplementation (no SC2 code copied, no assets); add "not affiliated with Blizzard Entertainment" to README before publishing

## References

| Context | Where |
|---|---|
| E9 spec | `docs/superpowers/specs/2026-04-16-e9-fog-of-war-design.md` |
| E9 plan | `docs/superpowers/plans/2026-04-16-e9-fog-of-war.md` |
| Blog | `docs/_posts/2026-04-16-mdp03-what-the-probe-sees.md` |
| E8 references | `git show 62e6f03:HANDOVER.md` |

## Environment

*Unchanged — `git show 62e6f03:HANDOVER.md`*
