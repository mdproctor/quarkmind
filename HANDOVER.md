# Handover — 2026-04-16

**Head commit:** `1ca2233` — docs: session handover 2026-04-15
**Previous handover:** `git show HEAD:HANDOVER.md` (that's the previous one — this session's changes are all uncommitted)

## What Changed This Session

**Pathfinding bugs fixed (emulated game is now wallsafe):**

- **A* `nearestWalkable` radius cap** — spiral search capped at `Math.max(width,height)=64`; target (224,224) needs radius 161. Fixed: clamp x/y to grid bounds before searching (`AStarPathfinder.java`).
- **PathfindingMovement fallback** — empty path fell back to DirectMovement (through walls). Fixed: stay put when >1.5 tiles from target; direct movement only for final approach (<1.5 tiles).
- **Physics wall enforcement** — independent layer in `EmulatedGame.enforceWall()`: every movement call checked against `WalkabilityGrid`; blocked positions trigger `movementStrategy.invalidatePath()` so A* recomputes next tick. Wired via `game.setWalkabilityGrid(grid)` in `EmulatedEngine.joinGame()`.
- **Stop-to-fight** — enemies within attack range hold position instead of walking through friendlies.
- **Root cause**: the unit going through the wall was `unit-200` — a scout probe sent by `DroolsScoutingTask` to `Point2d(224,224)` (real SC2 coords, outside the 64×64 emulated map). Not an enemy. Diagnosed via `grep` on profile-scoped file log.

**Tests:** 363 → 368. New: `wallPhysicsBlocksUnitRegardlessOfMovementStrategy`, `enemyStopsToFightWhenInMeleeRange`, `enemyRespectsWallWithPathfinding`, `enemyMovesWhenNoFriendlyInRange`, `enemyUnitRendersAtCorrectCanvasPosition` (Playwright).

**CLAUDE.md:** added `setWalkabilityGrid` and `spawnEnemyUnit` to test helper lists.

**Garden:** 4 entries submitted — A* radius cap gotcha, stop-to-fight test ordering gotcha, Quarkus dev-mode compile-at-startup gotcha, profile-scoped file logging technique.

**Blog:** `docs/_posts/2026-04-16-mdp01-defending-the-wall.md`

## Immediate Next Step

**E8: Terrain height, ramps, vision, miss chance** — parked in `IDEAS.md`. Extend `WalkabilityGrid` → `TerrainGrid` with high/low/wall values; ramp walkability direction rules; 25% miss chance for ranged attacks from low ground; visualizer height shading.

**Or: TacticsTask extension** — GOAP has real pathfinding now; retreat target could be actual base rather than MAP_CENTER; Kite and Focus-fire actions possible.

## Open Questions / Blockers

*Unchanged — `git show HEAD:HANDOVER.md`* (SC2Engine.tick() ownership, ReplayEngine profile, 7 unparseable AI Arena replays, EmulatedGame growing)

**New:**
- `DroolsScoutingTask` dispatches to SC2 map coords (224,224) — works now (clamped to 63,63) but scout goes to the far corner of the emulated map, which isn't meaningful. The scouting target should be configurable per-engine or the ScoutingTask should respect the actual map bounds.

## References

| Context | Where |
|---|---|
| E8 idea | `IDEAS.md` |
| Blog | `docs/_posts/2026-04-16-mdp01-defending-the-wall.md` |
| Previous handover | `git show HEAD:HANDOVER.md` |
| Garden entries | `~/.hortora/garden/java/GE-20260415-fb675d.md`, `GE-20260416-53d13c.md`, `quarkus/GE-20260416-1a2d0e.md`, `GE-20260416-99d4c6.md` |

## Environment

*Unchanged — `git show HEAD:HANDOVER.md`*
