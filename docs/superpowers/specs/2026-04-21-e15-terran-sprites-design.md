# E15 Design: Terran Cartoon Sprites + Team Colour System

**Date:** 2026-04-21  
**Epic:** #83 (3D Visualizer + Cartoon Sprites)  
**Depends on:** E14 (Three.js visualizer, Protoss sprites, `makeDirTextures` infrastructure)

---

## Goal

Add cartoon sprites for three Terran units (Marine, Marauder, Medivac) and retrofit the existing Protoss sprites with a team-colour decal system so all unit types correctly distinguish friendly from enemy regardless of which race the player controls.

---

## 1. Sprite API — `makeDirTextures` + `teamColor`

`makeDirTextures(drawFn, teamColor, size=128)` gains a required `teamColor` string parameter. It passes `teamColor` as a 4th argument to every draw function call:

```js
drawFn(ctx, size, dir, teamColor)
```

Two module-level constants:

```js
const TEAM_COLOR_FRIENDLY = '#4488ff';
const TEAM_COLOR_ENEMY    = '#ff4422';
```

Every draw function signature becomes `drawX(ctx, S, dir, teamColor)`. The `teamColor` is applied to SC2-style decal zones — specific regions that carry player colour in the original game — leaving the unit's body in its canonical palette:

| Unit | Decal zones |
|------|-------------|
| Probe | Outer shell highlights |
| Zealot | Blade energy colour |
| Stalker | Eye glow |
| Marine | Visor stripe, shoulder pads |
| Marauder | Visor slit, knee plates |
| Medivac | Engine glow, running lights |
| Unknown fallback | Spike tips, eye colour |

---

## 2. Material Dispatch

`initSpriteMaterials()` calls `makeDirTextures` twice per unit type (once per team) and stores the results in `UNIT_MATS` with flat string keys:

```js
UNIT_MATS['PROBE_F']     = makeDirTextures(drawProbe,    TEAM_COLOR_FRIENDLY);
UNIT_MATS['PROBE_E']     = makeDirTextures(drawProbe,    TEAM_COLOR_ENEMY);
UNIT_MATS['ZEALOT_F']    = makeDirTextures(drawZealot,   TEAM_COLOR_FRIENDLY);
UNIT_MATS['ZEALOT_E']    = makeDirTextures(drawZealot,   TEAM_COLOR_ENEMY);
UNIT_MATS['STALKER_F']   = makeDirTextures(drawStalker,  TEAM_COLOR_FRIENDLY);
UNIT_MATS['STALKER_E']   = makeDirTextures(drawStalker,  TEAM_COLOR_ENEMY);
UNIT_MATS['MARINE_F']    = makeDirTextures(drawMarine,   TEAM_COLOR_FRIENDLY);
UNIT_MATS['MARINE_E']    = makeDirTextures(drawMarine,   TEAM_COLOR_ENEMY);
UNIT_MATS['MARAUDER_F']  = makeDirTextures(drawMarauder, TEAM_COLOR_FRIENDLY);
UNIT_MATS['MARAUDER_E']  = makeDirTextures(drawMarauder, TEAM_COLOR_ENEMY);
UNIT_MATS['MEDIVAC_F']   = makeDirTextures(drawMedivac,  TEAM_COLOR_FRIENDLY);
UNIT_MATS['MEDIVAC_E']   = makeDirTextures(drawMedivac,  TEAM_COLOR_ENEMY);
UNIT_MATS['UNKNOWN_F']   = makeDirTextures(drawEnemy,    TEAM_COLOR_FRIENDLY);
UNIT_MATS['UNKNOWN_E']   = makeDirTextures(drawEnemy,    TEAM_COLOR_ENEMY);
```

Dispatch at sprite creation:

```js
const key  = u.type + (isEnemy ? '_E' : '_F');
const mats = UNIT_MATS[key] ?? UNIT_MATS['UNKNOWN_' + (isEnemy ? 'E' : 'F')];
```

Unknown unit types (races not yet drawn) fall through to the appropriately coloured blob fallback without error.

---

## 3. Flying Units — Medivac Y-offset

A module-level constant:

```js
const FLYING_UNITS = new Set(['MEDIVAC']);
```

Sprite Y-position at spawn:

```js
const y = FLYING_UNITS.has(u.type) ? TILE * 1.5 : TILE * 0.65;
sp.position.set(wp.x, y, wp.z);
```

The same offset applies to the 3D sphere model. No other flying-specific logic in E15; Medivac moves and faces like a ground unit for now.

---

## 4. Config Panel — New Wave Types

`visualizer.html` config dropdown gains `MARAUDER` and `MEDIVAC` entries alongside the existing `MARINE` and `ZERGLING`. No Java changes required — `EmulatedConfig` already accepts any `UnitType` string.

---

## 5. Testing

### Unit tests

No Java-side changes, so no new Java unit tests.

### JavaScript sprite smoke tests (via Playwright canvas inspection)

Each draw function is invoked for all 4 directions × 2 team colours and asserts:
- No exception thrown
- Canvas centre pixel alpha > 0 (something was drawn)

`UNIT_MATS` key coverage: assert all 7 unit types × 2 teams resolve to a non-null 4-element array after `initSpriteMaterials()`.

### Playwright E2E (`@Tag("browser")`)

**`VisualizerRenderTest` extensions:**
- Spawn MARINE, MARAUDER, MEDIVAC wave types via config panel (one per test); assert `window.__test.enemyCount()` matches configured wave count for each
- Assert Medivac screen Y is higher (lower pixel Y) than same-position Marine via `window.__test.worldToScreen`

**Happy path:** each wave type renders the correct sprite count  
**Correctness:** all draw functions produce non-transparent output for all dirs × team combos  
**Robustness:** unknown unit type resolves to `UNKNOWN_E`/`UNKNOWN_F` fallback without JS error  

---

## Out of Scope for E15

- Per-player colour (more than 2 teams) — the `teamColor` constant approach extends naturally; wire-up deferred
- Medivac flight path / altitude variation during movement
- Zerg sprites (E16)
- Combat indicators (E17)
- Replay controls (E18)
