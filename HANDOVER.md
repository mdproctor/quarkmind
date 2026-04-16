# Handover — 2026-04-16

**Head commit:** `6936ca8` — docs: add blog entry 2026-04-16 — high ground (E8)

## What Changed This Session

**E8: Terrain height — complete (GitHub #65, closed)**

- `TerrainGrid` replaces `WalkabilityGrid` everywhere — 4-value height model (HIGH/LOW/RAMP/WALL)
- Map layout: y>18 = HIGH (enemy staging), y<18 = LOW (nexus), y=18 x=11–13 = RAMP, rest = WALL
- 25% miss chance for ranged attacks from LOW→HIGH in `resolveCombat()` — `missesHighGround()` guard before `computeEffective()`; null/melee/RAMP bypassed; seeded `Random` injectable via `setRandomForTesting()`
- Terrain REST API (`/qa/emulated/terrain`) now returns `highGround` (2880 tiles) + `ramps` (3 tiles)
- Visualizer: topographic shading — HIGH=tan/brown, RAMP=mid-brown, WALL=dark-grey, LOW=bare canvas
- Playwright `playwright` Maven profile added; `@Tag("browser")` now consistently on all pixel-sampling tests
- **Log rotation + shutdown config** — `quarkus.shutdown.timeout=10S`, `%emulated.quarkus.log.file.rotation.*` (20M/3-backup/rotate-on-boot); CLAUDE.md documents kill-first rule for log cleanup

Executed via subagent-driven development (7 tasks, spec + code quality review per task). 378 surefire tests.

**Garden submissions (3 PRs):**
- Hortora/garden#63 — Maven `*IT` suffix silently skipped by `mvn test`
- Hortora/garden#64 — Anonymous `Random` subclass for deterministic probability tests  
- Hortora/garden#65 — Quarkus deleted log file causes invisible disk usage

**Blog:** `docs/_posts/2026-04-16-mdp02-high-ground.md`

## Immediate Next Step

**E9 candidates (no spec yet, in IDEAS.md):**
1. **Fog of war** — explicitly deferred from E8; full system-level feature (units on HIGH invisible to LOW without spotter)
2. **TacticsTask extension** — GOAP retreat to actual base (not MAP_CENTER), kite, focus-fire
3. **Scouting fix** — `DroolsScoutingTask` still dispatches to SC2 coords (224,224), clamped to corner (63,63); scouting target should respect emulated map bounds

Start with brainstorm to pick E9.

## Open Questions / Blockers

*Unchanged — `git show 2e7c934:HANDOFF.md` (2026-04-14 handover)*

**New:**
- `DroolsScoutingTask` dispatches to (224,224) — still clamped to (63,63), scout goes to far corner, not meaningful
- Log rotation config added; verify it works correctly on first emulated run (rotate-on-boot should create fresh file each start)

## References

| Context | Where |
|---|---|
| E8 spec | `docs/superpowers/specs/2026-04-16-e8-terrain-height-design.md` |
| E8 plan | `docs/superpowers/plans/2026-04-16-e8-terrain-height.md` |
| Blog | `docs/_posts/2026-04-16-mdp02-high-ground.md` |
| Visualizer screenshot | `docs/blog/assets/e8-high-ground.png` |
| Previous handover | `git show 2e7c934:HANDOFF.md` |

## Environment

*Unchanged — `git show 2e7c934:HANDOFF.md`*
