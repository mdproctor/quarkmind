# SC2 Emulation Engine — Phase E7: Pathfinding
**Date:** 2026-04-15
**Supersedes:** `2026-04-15-e6-enemy-retreat-design.md` (extends, does not replace)

---

## Context

E6 gave enemies a retreat mechanic. All movement — friendly, enemy, retreating — still
uses `stepToward`: Euclidean straight-line interpolation that ignores terrain. Units walk
through walls, chokepoints have no tactical meaning, and the map is topologically flat.

E7 introduces terrain-aware pathfinding. A 64×64 `WalkabilityGrid` defines a synthetic
map with a horizontal wall and 3-tile chokepoint. `AStarPathfinder` computes 8-directional
paths around obstacles. A `MovementStrategy` abstraction keeps all existing tests stable
(they default to straight-line `DirectMovement`); pathfinding tests opt in explicitly via
`PathfindingMovement`.

The same `WalkabilityGrid` and `AStarPathfinder` will be used unchanged by the real SC2
engine when ocraft's `PathingGrid` bitmap is wired in — no code duplication.

---

## Phase Roadmap

| Phase | Mechanic | Adds |
|---|---|---|
| E5 | Damage types, armour, Hardened Shield | SC2-accurate combat |
| E6 | Enemy retreat & regroup | Smarter opponent |
| **E7** | Pathfinding, terrain, MovementStrategy | Terrain-aware movement |
| E8 | Terrain height, ramps, vision, miss chance | Full terrain fidelity |

---

## Scope

**In scope:**
- `WalkabilityGrid` in `domain/` — 64×64 boolean grid, `emulatedMap()` factory, `fromPathingGrid()` factory stub
- `AStarPathfinder` in `domain/` — stateless 8-directional A*, returns `List<Point2d>` waypoints
- `MovementStrategy` interface in `sc2/emulated/` — `advance(tag, current, target, speed)` + `clearUnit(tag)`
- `DirectMovement` in `sc2/emulated/` — wraps `stepToward`, default (preserves all existing tests)
- `PathfindingMovement` in `sc2/emulated/` — A* based, manages waypoint queues per unit
- `EmulatedGame` changes — `movementStrategy` field, delegation, `setMovementStrategy()` helper
- `EmulatedTerrainResource` — `GET /qa/emulated/terrain` QA endpoint (walls as sparse list)
- Visualizer — static terrain layer drawn once at startup from terrain endpoint
- Tests: `WalkabilityGridTest`, `AStarPathfinderTest`, `PathfindingMovementTest`, `EmulatedGameTest` additions, `EmulatedTerrainIT`

**Explicitly out of scope:**
- Terrain height, ramps, vision advantage, miss chance (E8 — see `IDEAS.md`)
- Real SC2 PathingGrid wiring (future — `fromPathingGrid()` stub prepared but not called)
- Unit collision / separation avoidance
- Diagonal movement restrictions (SC2 pathing rules at ramps)

---

## Part 1: `WalkabilityGrid` (`domain/`)

Pure Java record-style class — no CDI, no framework. Wraps `boolean[][]`.

```java
public final class WalkabilityGrid {

    private final boolean[][] walkable;  // [x][y], true = walkable
    private final int width;
    private final int height;

    public WalkabilityGrid(int width, int height, boolean[][] walkable) { ... }

    public boolean isWalkable(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return false;
        return walkable[x][y];
    }

    public int width()  { return width; }
    public int height() { return height; }
```

### 1a: `emulatedMap()` static factory

64×64 grid. All tiles walkable except a **horizontal wall at `y=18`** spanning the full
width, with a **3-tile chokepoint gap at `x=11, 12, 13`**:

```
y=26  ·  ·  · (enemy staging at 26,26)
 ...  open terrain
y=19  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ...
y=18  █  █  █  █  █  █  █  █  █  █  █  ·  ·  ·  █  █  █  █  ...
      x=0..10 (wall)                   x=11,12,13 (gap)  x=14..63 (wall)
y=17  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ...
 ...  open terrain
y=8   ·  ·  ·  ·  ·  ·  ·  ·  · (nexus at 8,8)
```

Rationale: the straight-line path from staging (26,26) to nexus (8,8) would cross
`y=18` at `x=18` — 5 tiles right of the gap. All units must detour to `x=11–13`.
The gap is 3 tiles left of the nexus x-coordinate, giving the player a shorter approach
to the chokepoint than the enemy.

```java
public static WalkabilityGrid emulatedMap() {
    boolean[][] grid = new boolean[64][64];
    // default: all walkable
    for (boolean[] col : grid) Arrays.fill(col, true);
    // wall at y=18, except gap at x=11,12,13
    for (int x = 0; x < 64; x++) {
        if (x < 11 || x > 13) grid[x][18] = false;
    }
    return new WalkabilityGrid(64, 64, grid);
}
```

### 1b: `fromPathingGrid()` factory stub (for real SC2 — future wiring)

```java
/**
 * Constructs a WalkabilityGrid from ocraft's PathingGrid bitmap.
 * Bit encoding: index = x + y*width; bit = (data[index/8] >> (7 - index%8)) & 1
 * 1 = walkable, 0 = wall.
 * Called by ObservationTranslator when real SC2 is connected (future phase).
 */
public static WalkabilityGrid fromPathingGrid(byte[] data, int width, int height) {
    boolean[][] grid = new boolean[width][height];
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int index = x + y * width;
            grid[x][y] = ((data[index / 8] >> (7 - index % 8)) & 1) == 1;
        }
    }
    return new WalkabilityGrid(width, height, grid);
}
```

---

## Part 2: `AStarPathfinder` (`domain/`)

Stateless class — no fields, no CDI. Single public method:

```java
public final class AStarPathfinder {

    public List<Point2d> findPath(WalkabilityGrid grid, Point2d from, Point2d to) { ... }
}
```

Returns an ordered list of **tile-centre world coordinates** from `from` to `to`, not
including the start position. Returns empty list if start==goal tile or goal unreachable.

### Algorithm

Standard A* with **8-directional movement**:

| Step type | Cost |
|---|---|
| Cardinal (N/S/E/W) | 1.0 |
| Diagonal (NE/NW/SE/SW) | √2 ≈ 1.414 |

**Heuristic:** Euclidean distance to goal tile — admissible for 8-directional movement.

**Coordinate conversion:**
```java
int tileX(Point2d p) { return (int) p.x(); }
int tileY(Point2d p) { return (int) p.y(); }
Point2d tileCentre(int x, int y) { return new Point2d(x + 0.5f, y + 0.5f); }
```

**Nearest-walkable snap:** if `from` or `to` maps to an unwalkable tile, snap to the
nearest walkable tile before running A* (prevents stuck units at building edges).

**Internal node:**
```java
private record Node(int x, int y, double g, double f, Node parent) {}
```

**Visited tracking:** `boolean[width][height]` array — O(1) lookup, reset each call.

**Output:** tile centres along the path. The final waypoint is the centre of the goal
tile, not the exact `to` position — `PathfindingMovement` drives the unit to the exact
target once it reaches the final waypoint.

---

## Part 3: `MovementStrategy` interface (`sc2/emulated/`)

```java
public interface MovementStrategy {

    /**
     * Advance one tick: return the new world position for a unit moving toward target.
     * Called every tick for each unit that has a non-null target.
     */
    Point2d advance(String unitTag, Point2d current, Point2d target, double speed);

    /**
     * Called when a unit is removed from the game (dead or transferred to staging).
     * Implementations should clean up any per-unit state for that unit.
     */
    default void clearUnit(String unitTag) {}

    /**
     * Called from EmulatedGame.reset() — clear ALL per-unit state for a fresh game.
     */
    default void reset() {}
}
```

---

## Part 4: `DirectMovement` (`sc2/emulated/`)

One-liner wrapper around the existing `stepToward`. No state. Default implementation —
all existing tests use this implicitly.

```java
public class DirectMovement implements MovementStrategy {

    @Override
    public Point2d advance(String unitTag, Point2d current, Point2d target, double speed) {
        return EmulatedGame.stepToward(current, target, speed);
    }
}
```

`clearUnit` is a no-op (inherited default).

---

## Part 5: `PathfindingMovement` (`sc2/emulated/`)

Manages per-unit waypoint queues. Path is computed once per target change.

```java
public class PathfindingMovement implements MovementStrategy {

    private final WalkabilityGrid       grid;
    private final AStarPathfinder       pathfinder = new AStarPathfinder();
    private final Map<String, Deque<Point2d>> waypoints  = new HashMap<>();
    private final Map<String, Point2d>       lastTargets = new HashMap<>();

    public PathfindingMovement(WalkabilityGrid grid) { this.grid = grid; }

    @Override
    public Point2d advance(String unitTag, Point2d current, Point2d target, double speed) {
        // Recompute path if target changed
        if (!target.equals(lastTargets.get(unitTag))) {
            List<Point2d> path = pathfinder.findPath(grid, current, target);
            waypoints.put(unitTag, new ArrayDeque<>(path));
            lastTargets.put(unitTag, target);
        }

        Deque<Point2d> queue = waypoints.get(unitTag);
        if (queue == null || queue.isEmpty()) {
            return EmulatedGame.stepToward(current, target, speed);
        }

        Point2d next    = queue.peek();
        Point2d newPos  = EmulatedGame.stepToward(current, next, speed);

        // Advance to next waypoint when current one is reached
        if (EmulatedGame.distance(newPos, next) < 0.1) {
            queue.poll();
        }
        return newPos;
    }

    @Override
    public void clearUnit(String unitTag) {
        waypoints.remove(unitTag);
        lastTargets.remove(unitTag);
    }
}
```

**Key behaviours:**
- Path is recomputed when `target` differs from `lastTargets` (covers intent changes
  and retreat target changes)
- When waypoint queue is empty (path exhausted or no path found), falls back to
  `stepToward` directly to target — unit still arrives even if A* returns empty
- `clearUnit` is called from `resolveCombat()` dead-unit cleanup and from
  `tickEnemyRetreat()` staging transfer, same pattern as existing cleanups

---

## Part 6: `EmulatedGame` changes

### 6a: New field

```java
private MovementStrategy movementStrategy = new DirectMovement();
```

### 6b: `moveFriendlyUnits()` — delegate to strategy

```java
// Before:
Point2d newPos = stepToward(u.position(), target, unitSpeed);

// After:
Point2d newPos = movementStrategy.advance(u.tag(), u.position(), target, unitSpeed);
```

Same change in `moveEnemyUnits()`.

### 6c: Dead-unit cleanup — call `clearUnit`

In `resolveCombat()` friendly dead-unit block:
```java
movementStrategy.clearUnit(u.tag());
```

In `resolveCombat()` enemy dead-unit block:
```java
movementStrategy.clearUnit(u.tag());
```

In `tickEnemyRetreat()` staging transfer (unit leaves `enemyUnits`):
```java
movementStrategy.clearUnit(u.tag());
```

### 6d: New package-private test helper

```java
void setMovementStrategy(MovementStrategy s) { this.movementStrategy = s; }
```

### 6e: `reset()` — reset strategy state

```java
movementStrategy.clearUnit("*");  // not viable — clearUnit is per-tag
```

Actually: call `movementStrategy = new DirectMovement()` is wrong (loses the configured
strategy). Instead, add a `reset()` default method to `MovementStrategy`:

```java
// In MovementStrategy interface:
default void reset() {}  // clear all per-unit state

// In PathfindingMovement:
@Override public void reset() { waypoints.clear(); lastTargets.clear(); }
```

Call `movementStrategy.reset()` in `EmulatedGame.reset()`.

---

## Part 7: `EmulatedTerrainResource` QA endpoint

New class in `qa/` package, annotated `@UnlessBuildProfile("prod")`:

```java
@Path("/qa/emulated/terrain")
@Produces(MediaType.APPLICATION_JSON)
@UnlessBuildProfile("prod")
public class EmulatedTerrainResource {

    @GET
    public TerrainResponse getTerrain() {
        WalkabilityGrid grid = WalkabilityGrid.emulatedMap();
        List<int[]> walls = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                if (!grid.isWalkable(x, y)) walls.add(new int[]{x, y});
            }
        }
        return new TerrainResponse(grid.width(), grid.height(), walls);
    }
}

public record TerrainResponse(int width, int height, List<int[]> walls) {}
```

Only wall tiles are listed — all others are implicitly walkable. Sparse format keeps
the response small (the emulated map has 61 wall tiles).

---

## Part 8: Visualizer — terrain layer

`visualizer.js` fetches terrain once at startup, before the WebSocket connects:

```javascript
async function loadTerrain() {
    const resp  = await fetch('/qa/emulated/terrain');
    const data  = await resp.json();
    const g     = new PIXI.Graphics();
    const scale = TILE_SIZE;  // pixels per tile (same scale as unit sprites)

    data.walls.forEach(([x, y]) => {
        g.beginFill(0x333333, 0.75);
        g.drawRect(x * scale, y * scale, scale, scale);
        g.endFill();
    });

    app.stage.addChildAt(g, 0);  // terrain behind all unit layers
}
```

No per-tick updates — terrain is static. The terrain layer sits at z-index 0 (bottom),
beneath friendly, enemy, staging, and HUD layers.

---

## Part 9: Testing

### `WalkabilityGridTest` (plain JUnit, new — `domain/`)

| Test | Asserts |
|---|---|
| `emulatedMapNexusTileIsWalkable` | `isWalkable(8,8)` = true |
| `emulatedMapStagingTileIsWalkable` | `isWalkable(26,26)` = true |
| `emulatedMapWallTileIsBlocked` | `isWalkable(20,18)` = false |
| `emulatedMapChokeGapIsWalkable` | `isWalkable(12,18)` = true |
| `emulatedMapChokeEdgesAreBlocked` | `isWalkable(10,18)` = false, `isWalkable(14,18)` = false |
| `outOfBoundsReturnsFalse` | `isWalkable(-1,0)`, `isWalkable(64,0)`, `isWalkable(0,64)` all false |
| `fromPathingGrid_bit1IsWalkable` | byte `0xFF` → all tiles walkable |
| `fromPathingGrid_bit0IsBlocked` | byte `0x00` → all tiles blocked |

### `AStarPathfinderTest` (plain JUnit, new — `domain/`)

| Test | Asserts |
|---|---|
| `pathOnOpenMap_isNonEmpty` | 5×5 all-walkable, (0,0)→(4,4): non-empty path |
| `pathOnOpenMap_allWaypointsWalkable` | all returned `Point2d` map to walkable tiles |
| `pathOnOpenMap_endsNearGoal` | last waypoint within 1 tile of (4,4) |
| `pathAroundWall_doesNotCrossWall` | 10×10 grid, wall at y=5 except gap at x=5: no waypoint at `y==5` unless `x` rounds to 5 |
| `pathAroundWall_reachesGoal` | same grid, path is non-empty and ends near goal |
| `sameStartAndGoal_returnsEmpty` | `findPath(grid, (8,8), (8,8))` → empty |
| `unreachableGoal_returnsEmpty` | goal tile fully enclosed by walls → empty |
| `pathOnEmulatedMap_nexusToStaging` | `emulatedMap()`, (8,8)→(26,26): non-empty |
| `pathOnEmulatedMap_passesNearChokepoint` | no waypoint has `tileY==18` with `tileX` outside `[11,13]` |
| `pathOnEmulatedMap_stagingToNexus` | reverse direction also routes through chokepoint |

### `PathfindingMovementTest` (plain JUnit, new — `sc2/emulated/`)

| Test | Asserts |
|---|---|
| `unitMovesTowardFirstWaypoint` | after one `advance`, position closer to first waypoint than start |
| `unitArrivalAfterManyTicks` | enough `advance` calls → distance to target < 1 tile |
| `targetChangeClearsOldPath` | change target mid-path: new direction on next `advance` |
| `clearUnitAllowsCleanRestart` | after `clearUnit`, same tag gets a fresh path on next call |
| `noPathFallsBackToStepToward` | unreachable target → unit still moves (directly toward target) |
| `resetClearsAllUnits` | after `reset()`, no stale waypoints for any unit |

### `EmulatedGameTest` additions (plain JUnit, existing class)

```java
// Enable pathfinding for these tests:
game.setMovementStrategy(new PathfindingMovement(WalkabilityGrid.emulatedMap()));
```

| Test | Asserts |
|---|---|
| `withPathfinding_unitReachesTargetAcrossWall` | unit targeting (12, 22) from (8, 8) arrives (enough ticks) |
| `withPathfinding_unitDoesNotCrossWall` | mid-path, no unit position has `tileY==18` unless `tileX` in `[11,13]` |
| `directMovementDefaultIsUnchanged` | without `setMovementStrategy`, existing movement behaviour preserved |

### `EmulatedTerrainIT` (`@QuarkusTest`, new)

| Test | Asserts |
|---|---|
| `terrainEndpointReturnsExpectedDimensions` | `GET /qa/emulated/terrain` → `width=64`, `height=64` |
| `terrainEndpointIncludesWallTile` | response `walls` contains `[20,18]` |
| `terrainEndpointExcludesGapTile` | response `walls` does not contain `[12,18]` |

### Playwright E2E (tagged `@Tag("browser")`, existing `VisualizerRenderTest`)

| Test | Asserts |
|---|---|
| `wallTilesRenderDarkerThanOpenTiles` | pixel at wall tile `(20,18)` is darker than open tile `(12,17)` via `window.__test` API |

---

## Part 10: GitHub Issue Structure

| Type | Title |
|---|---|
| Epic | E7: Pathfinding |
| Issue | #E7-1: `WalkabilityGrid` in `domain/` — 64×64 grid, `emulatedMap()`, `fromPathingGrid()` |
| Issue | #E7-2: `AStarPathfinder` in `domain/` — 8-directional A*, tile centres |
| Issue | #E7-3: `MovementStrategy` interface + `DirectMovement` + `PathfindingMovement` |
| Issue | #E7-4: `EmulatedGame` — `movementStrategy` field, delegation, `clearUnit`, `reset()` |
| Issue | #E7-5: `EmulatedTerrainResource` — `GET /qa/emulated/terrain` QA endpoint |
| Issue | #E7-6: Visualizer — static terrain layer from terrain endpoint |
| Issue | #E7-7: Tests — `WalkabilityGridTest`, `AStarPathfinderTest`, `PathfindingMovementTest`, `EmulatedGameTest` additions, `EmulatedTerrainIT` |

---

## Context Links

- E6 spec: `docs/superpowers/specs/2026-04-15-e6-enemy-retreat-design.md`
- Ideas log: `IDEAS.md` (E8: terrain height)
- Library research §2.2: gdx-ai pathfinding (deferred — JVM-only, no GraalVM)
- GitHub: mdproctor/quarkmind
