# E16 — Zerg Sprites Design

**Date:** 2026-04-22  
**Builds on:** E15 (Terran sprites, `makeDirTextures`, `FLYING_UNITS`, `UNIT_MATS` dispatch)

## Goal

Add cartoon sprite draw functions for four Zerg units — Zergling, Roach, Hydralisk, Mutalisk — following the exact pattern established in E14/E15. Update `ShowcaseResource` to seed all three races simultaneously.

## Scope

| Unit | Type | Flying |
|------|------|--------|
| Zergling | Quadrupedal insectoid, scythe blades | No |
| Roach | Low-slung, heavy armour, 6-legged | No |
| Hydralisk | Upright serpentine, cobra hood, ranged | No |
| Mutalisk | Manta silhouette, long tail | Yes |

All other Zerg units in `UnitType` continue to fall back to `UNKNOWN_F`/`UNKNOWN_E`.

## Visual Language

**Palette** — Classic SC2 Zerg:

| Zone | Colour |
|------|--------|
| Carapace (body) | `#2a0a3a` dark purple |
| Flesh / undertone | `#5c1a6e` mid purple |
| Highlight / plates | `#8b3a9e` bright purple |
| Eyes | `#ffe066` yellow |
| Team decal | `teamColor` (blue friendly / red enemy) |

**Team colour decal strategy — bio-sac / pustule glow.** Organic glands pulse with `teamColor` using a radial gradient with `shadowBlur`. Placement per unit:

- **Zergling** — one belly sac + two small shoulder sacs
- **Roach** — three acid gland sacs along the back ridge
- **Hydralisk** — one throat sac + two shoulder pustules
- **Mutalisk** — two wing-joint sacs (one per wing root)

## Implementation

### Draw functions (new)

Four functions added to `visualizer.js`, each with the standard signature:

```js
function drawZergling(ctx, S, dir, teamColor)  // quadrupedal, scythe blades
function drawRoach(ctx, S, dir, teamColor)      // wide, low, armoured
function drawHydralisk(ctx, S, dir, teamColor)  // serpentine, upright, hooded
function drawMutalisk(ctx, S, dir, teamColor)   // manta wings, flying
```

All four render 4 directional variants (`dir` 0–3: front, right, back, left). `drawMutalisk` may use a near-top-down perspective for all directions given it flies.

### FLYING_UNITS update

```js
const FLYING_UNITS = new Set(['MEDIVAC', 'MUTALISK']);
```

Mutalisk spawns at `TILE * 1.5` Y (same as Medivac).

### `initSpriteMaterials` additions

```js
UNIT_MATS['ZERGLING_F']  = makeDirTextures(drawZergling,  TEAM_COLOR_FRIENDLY);
UNIT_MATS['ZERGLING_E']  = makeDirTextures(drawZergling,  TEAM_COLOR_ENEMY);
UNIT_MATS['ROACH_F']     = makeDirTextures(drawRoach,     TEAM_COLOR_FRIENDLY);
UNIT_MATS['ROACH_E']     = makeDirTextures(drawRoach,     TEAM_COLOR_ENEMY);
UNIT_MATS['HYDRALISK_F'] = makeDirTextures(drawHydralisk, TEAM_COLOR_FRIENDLY);
UNIT_MATS['HYDRALISK_E'] = makeDirTextures(drawHydralisk, TEAM_COLOR_ENEMY);
UNIT_MATS['MUTALISK_F']  = makeDirTextures(drawMutalisk,  TEAM_COLOR_FRIENDLY);
UNIT_MATS['MUTALISK_E']  = makeDirTextures(drawMutalisk,  TEAM_COLOR_ENEMY);
```

Total `UNIT_MATS` keys after E16: 22 (6 Protoss + 6 Terran + 8 Zerg + 2 Unknown).

### `smokeTestDrawFn` update

Add `drawZergling`, `drawRoach`, `drawHydralisk`, `drawMutalisk` to the function lookup table in the `window.__test` hook.

### `ShowcaseResource`

Add Zerg units at map positions away from existing Protoss/Terran clusters. Minimum: one friendly and one enemy Zergling, one Roach, one Hydralisk, one Mutalisk — spread across the map so all sprite types are visible simultaneously.

## Tests

### `VisualizerRenderTest` (Playwright, `@Tag("browser")`)

- `smokeTestDrawFn` assertions for all 4 new draw functions — verifies function exists and renders non-zero centre alpha for both team colours
- `unitMatsKeys()` assertion updated: expect 22 keys including the 8 new Zerg entries
- Showcase unit/enemy counts updated to reflect Zerg additions
- `allSceneObjectsAreWithinMapBounds` already covers position correctness — no change needed

### Java / unit tests

No changes needed. `UnitType` already contains all four values. `GameStateInvariantTest` covers them generically.

## Files Changed

| File | Change |
|------|--------|
| `src/main/resources/META-INF/resources/visualizer.js` | 4 new draw functions, `FLYING_UNITS`, `initSpriteMaterials`, `smokeTestDrawFn` |
| `src/main/java/io/quarkmind/qa/ShowcaseResource.java` | Add Zerg units to seeded state |
| `src/test/java/.../VisualizerRenderTest.java` | Extended smoke + key count + unit count assertions |

## Non-Goals

- Sprites for remaining Zerg units (Queen, Lurker, Ultralisk, etc.) — future episode
- Animation or particle effects — static directional sprites only
- Java domain changes — `UnitType` enum already complete
