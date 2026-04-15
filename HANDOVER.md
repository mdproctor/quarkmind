# Handover — 2026-04-15

**Head commit:** `e9ca647` — docs: blog entry E5/E6/E7
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

**E5 — Damage types, armour, Hardened Shield:**
- `UnitAttribute` enum in `domain/`; 4 new `SC2Data` methods (`unitAttributes`, `armour`, `bonusDamageVs`, `hasHardenedShield`); corrected HP for 5 units (Immortal→200, Marine→45, Marauder→125, Roach→145, Hydralisk→90)
- `DamageCalculator` in `sc2/emulated/` — stateless, independently tested
- Issues #49–#53, milestone #1

**E6 — Enemy retreat & regroup:**
- `EnemyAttackConfig` gains `retreatHealthPercent` + `retreatArmyPercent`; default 30/50
- `EmulatedGame`: `retreatingUnits` set, `initialAttackSize`, `STAGING_POS` constant, `tickEnemyRetreat()` in tick loop
- Arrival threshold `>= 0.1` (not 0.5) — floating-point boundary fix
- Issues #54–#57, milestone #2

**E7 — Pathfinding:**
- `WalkabilityGrid` + `AStarPathfinder` in `domain/` — reusable by real SC2 engine
- Emulated map: 64×64, wall at y=18, gap at x=11–13
- `MovementStrategy` interface + `DirectMovement` (default, all existing tests unchanged) + `PathfindingMovement` (per-unit A* queues)
- `EmulatedTerrainResource`: `GET /qa/emulated/terrain` → sparse wall list
- Visualizer: static terrain layer drawn once at startup
- Issues #58–#64, milestone #3

**Also:** `%test.quarkus.http.test-port=0` fixes `DroolsScoutingRulesTest` port conflict; `IDEAS.md` created with E8 terrain height entry; 7 garden entries submitted.

**Test count:** 363 passing, 0 failures.

## Immediate Next Step

**E8: Terrain height, ramps, vision, miss chance** — parked in `IDEAS.md`. If proceeding, start brainstorming: extend `WalkabilityGrid` to `TerrainGrid` with high/low/wall values; ramp walkability direction rules; 25% miss chance for ranged attacks from low ground; visualizer height shading. `AStarPathfinder` is untouched.

**Or: TacticsTask extension** — `DroolsTacticsTask` spike exists (3 actions: MoveToEngage, Attack, Retreat). Future actions: Kite, Focus fire. Now that pathfinding exists, the retreat target in GOAP could be the actual base rather than MAP_CENTER.

**Or: Real SC2 wiring** — `WalkabilityGrid.fromPathingGrid()` stub is ready; `ObservationTranslator` needs to call it from `StartRaw.getPathingGrid()`.

## Open Questions / Blockers

*Unchanged from previous — `git show HEAD~1:HANDOVER.md`* (SC2Engine.tick() ownership, ReplayEngine profile, 7 unparseable AI Arena replays)

**New:**
- `EmulatedGame` growing (420+ lines) — `tickEnemyRetreat()` and `tickEnemyStrategy()` could split to `EnemyAI` class if it continues growing
- Shield regeneration not yet modelled (out-of-combat regen after ~10s in real SC2)

## References

| Context | Where |
|---|---|
| E5 spec | `docs/superpowers/specs/2026-04-15-e5-damage-types-armour-design.md` |
| E6 spec | `docs/superpowers/specs/2026-04-15-e6-enemy-retreat-design.md` |
| E7 spec | `docs/superpowers/specs/2026-04-15-e7-pathfinding-design.md` |
| E8 idea | `IDEAS.md` |
| Blog | `docs/_posts/2026-04-15-mdp01-armour-retreat-and-walls.md` |
| Garden | `~/.hortora/garden/GARDEN.md` (7 entries submitted this session) |
| Library research | `docs/library-research.md` |

## Environment

*Unchanged — `git show HEAD~1:HANDOVER.md`*
