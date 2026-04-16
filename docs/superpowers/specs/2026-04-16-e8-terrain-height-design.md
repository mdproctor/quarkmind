# E8: Terrain Height — Design Spec

**Date:** 2026-04-16
**Epic:** Emulated Physics

## Summary

Replace the binary `WalkabilityGrid` with a richer `TerrainGrid` that carries four height
values: `HIGH`, `LOW`, `RAMP`, and `WALL`. Wire height into combat (25% miss chance for
ranged attacks from low to high ground) and the visualizer (topographic shading). Vision
and ramp directionality are explicitly out of scope.

## Out of Scope

- **Vision / fog of war** — deferred to a dedicated epic. Full fog-of-war is the eventual
  goal; no half-measures here.
- **Ramp directionality** — ramp tiles are neutral height. No one-way traversal rules.
  Real SC2 ramps are always two-way.

---

## 1. `TerrainGrid` Data Model

`WalkabilityGrid` is deleted and replaced by `TerrainGrid` in `domain/` (plain Java,
no framework deps).

### Height enum

```java
public enum Height { HIGH, LOW, RAMP, WALL }
```

### `TerrainGrid` public API

| Method | Behaviour |
|---|---|
| `heightAt(int x, int y)` | Returns `Height` for the tile. Out-of-bounds → `WALL`. |
| `isWalkable(int x, int y)` | `heightAt(x,y) != WALL` — same contract as old `WalkabilityGrid`. |
| `width()` / `height()` | Grid dimensions. |
| `static emulatedMap()` | Canonical 64×64 emulated map (replaces `WalkabilityGrid.emulatedMap()`). |
| `static fromPathingGrid(byte[], int, int)` | Builds from ocraft bitmap. All walkable tiles return `LOW` until real SC2 height data is integrated. |

### Internal storage

`Height[][] grid` — indexed `[x][y]`, deep-copied on construction. No boolean array;
`isWalkable` derives from height.

### Call sites changed (`WalkabilityGrid` → `TerrainGrid`)

- `EmulatedGame` — field, setter, `enforceWall()`
- `EmulatedEngine` — `setWalkabilityGrid()` call in `joinGame()`
- `PathfindingMovement` / `AStarPathfinder` — accepts grid via constructor
- `EmulatedTerrainResource` — builds grid, serialises response
- `EmulatedGameTest` / `EmulatedEngineTest` — test helpers

---

## 2. Map Layout

The existing 64×64 map gains height. The wall at y=18 becomes the high/low boundary.

| Tiles | Height |
|---|---|
| `y > 18` (all x) | `HIGH` — enemy staging side, visually at top of screen |
| `y < 18` (all x) | `LOW` — nexus/defender side, visually at bottom |
| `y = 18, x = 11–13` | `RAMP` — the existing chokepoint gap |
| `y = 18, x = 0–10 and x = 14–63` | `WALL` — unchanged |

**Tactical implication:** enemies start on high ground and attack downhill. Player ranged
units defending from low ground have a 25% miss chance when shooting up at enemies before
they cross the ramp. Once enemies descend to low ground, both sides fight on equal footing.
This creates a real incentive to send a unit up to high ground to level the playing field.

---

## 3. Miss Chance in Combat

### Where it lives

The miss check is a guard in `EmulatedGame.resolveCombat()`, **before**
`DamageCalculator.computeEffective()` is called. `DamageCalculator` remains pure
(damage formula only).

### Conditions

A ranged attack **misses** (no damage dealt) when all of the following hold:

1. Attacker is on `LOW` ground — `terrainGrid.heightAt(attackerTile) == LOW`
2. Target is on `HIGH` ground — `terrainGrid.heightAt(targetTile) == HIGH`
3. The attack is ranged — `SC2Data.attackRange(attackerType) > 1.0`
4. `random.nextDouble() < 0.25`

Melee attacks, equal-height attacks, high→low attacks, and ramp-involved attacks are
never penalised.

### Null safety

If `terrainGrid` is null (mock, replay, plain unit-test contexts), the miss check is
skipped entirely. Existing behaviour is preserved with no code changes in those paths.

### Randomness

`EmulatedGame` holds a `Random` instance (constructed with a fixed seed). A package-private
`setRandomForTesting(Random r)` helper allows tests to inject a rigged `Random`
(e.g. `new Random() { double nextDouble() { return 0.0; } }` for always-hit,
`return 1.0` for always-miss).

---

## 4. Terrain REST API

`EmulatedTerrainResource` at `GET /qa/emulated/terrain` gains two new fields.
`walls` is unchanged; unspecified tiles are implicitly `LOW`.

```json
{
  "width": 64,
  "height": 64,
  "walls":      [[0, 18], [1, 18], ...],
  "highGround": [[0, 19], [1, 19], ...],
  "ramps":      [[11, 18], [12, 18], [13, 18]]
}
```

Note: array format is `[x, y]` to match the existing `walls` convention.

`TerrainResponse` record gains `List<int[]> highGround` and `List<int[]> ramps`.
The `walls` list is unchanged.

---

## 5. Visualizer Shading

`loadTerrain()` in `visualizer.js` draws a static shaded layer beneath the grid lines,
using a topographic colour scheme:

| Height | Colour | Alpha | Intent |
|---|---|---|---|
| `LOW` | none | — | Canvas background (`0x1a1a2e`) shows through — dark valley floor |
| `HIGH` | `0x8B6914` (warm tan/brown) | 0.55 | Elevated land |
| `RAMP` | `0x7A6040` (mid brown) | 0.40 | Transitional slope |
| `WALL` | `0x333333` (dark grey) | 0.85 | Impassable — unchanged |

The three lists from the API (`walls`, `highGround`, `ramps`) are rendered in a single
`Graphics` object. `loadTerrain()` already runs once at startup before the WebSocket
connects, so no changes to the call site are needed.

---

## 6. Testing

### `TerrainGridTest` (plain JUnit, new)

| Test | Assertion |
|---|---|
| `emulatedMapWallsCorrect` | y=18 tiles are WALL except x=11–13 |
| `emulatedMapRampCorrect` | x=11–13, y=18 are RAMP |
| `emulatedMapHighGroundCorrect` | y=19 sample tiles are HIGH |
| `emulatedMapLowGroundCorrect` | y=17 sample tiles are LOW |
| `isWalkableMatchesHeight` | WALL → false; HIGH/LOW/RAMP → true |
| `outOfBoundsIsWall` | Negative or ≥ dimension → WALL / not walkable |

### `EmulatedGameTest` (plain JUnit, additions)

| Test | Setup | Assertion |
|---|---|---|
| `rangedAttackLowToHighMisses` | Always-miss `Random`; Stalker on LOW, enemy on HIGH | Zero damage after N ticks |
| `rangedAttackLowToHighHits` | Never-miss `Random`; same positions | Normal damage applied |
| `meleeAttackLowToHighNeverMisses` | Always-miss `Random`; Zealot on LOW, enemy on HIGH | Full damage applied |
| `rangedAttackEqualHeightNoMiss` | Always-miss `Random`; both on LOW | Full damage applied |
| `rangedAttackHighToLowNoMiss` | Always-miss `Random`; Stalker on HIGH, enemy on LOW | Full damage applied |
| `rampAttackerNeutralNoMiss` | Always-miss `Random`; Stalker on RAMP | Full damage applied |

### `QaEndpointsTest` (`@QuarkusTest`, additions)

- Terrain response contains `highGround` and `ramps` arrays
- Spot-check: tile (0, 19) in `highGround`; tile (11, 18) in `ramps`
- `walls` list unchanged

---

## 7. File Inventory

| File | Change |
|---|---|
| `domain/WalkabilityGrid.java` | **Delete** |
| `domain/TerrainGrid.java` | **New** — `Height` enum + grid implementation |
| `sc2/emulated/EmulatedGame.java` | Field type, setter, `enforceWall()`, `resolveCombat()` miss guard, `setRandomForTesting()` |
| `sc2/emulated/EmulatedEngine.java` | `setWalkabilityGrid` → `setTerrainGrid` |
| `sc2/emulated/PathfindingMovement.java` | Type reference update |
| `qa/EmulatedTerrainResource.java` | Response record, grid construction |
| `visualizer.js` | `loadTerrain()` — parse `highGround`/`ramps`, draw shaded tiles |
| `EmulatedGameTest.java` | 6 new miss-chance tests, `setRandomForTesting` helper |
| `TerrainGridTest.java` | New test class |
| `QaEndpointsTest.java` | 3 new terrain endpoint assertions |
