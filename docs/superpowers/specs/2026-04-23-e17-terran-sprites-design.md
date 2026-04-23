# E17 Design: Remaining Terran Sprites

**Date:** 2026-04-23
**Episode:** E17
**Parent epic:** #83 (3D Visualizer)

## Goal

Complete 2D sprite coverage for all 10 remaining Terran units, bringing Terran from 3/13 to 13/13. Each unit gets a canvas 2D draw function following the established pattern (4 directions, 2 team colours, 128×128 px textures).

## Units in Scope

| Unit | Category | UNIT_MATS keys | Flying |
|------|----------|----------------|--------|
| Ghost | Ground infantry | `GHOST_F`, `GHOST_E` | No |
| Cyclone | Ground vehicle | `CYCLONE_F`, `CYCLONE_E` | No |
| Widow Mine | Ground vehicle | `WIDOW_MINE_F`, `WIDOW_MINE_E` | No |
| Siege Tank | Ground vehicle | `SIEGE_TANK_F`, `SIEGE_TANK_E` | No |
| Thor | Ground walker | `THOR_F`, `THOR_E` | No |
| Viking | Air | `VIKING_F`, `VIKING_E` | Yes |
| Raven | Air | `RAVEN_F`, `RAVEN_E` | Yes |
| Banshee | Air | `BANSHEE_F`, `BANSHEE_E` | Yes |
| Liberator | Air | `LIBERATOR_F`, `LIBERATOR_E` | Yes |
| Battlecruiser | Air | `BATTLECRUISER_F`, `BATTLECRUISER_E` | Yes |

**Out of scope:** Siege mode (SIEGE_TANK_SIEGE_*) — deferred until unit state is surfaced in GameStateBroadcast. Widow Mine burrowed/undeployed state — deferred for same reason. Viking ground mode — same.

## Draw Function Pattern

All 10 functions follow the identical signature and conventions as existing units:

```javascript
function drawGhost(ctx, S, dir, teamColor) {
  // dir=3 mirrors dir=1 via ctx.scale(-1,1)
  // dir=0 = front, dir=1 = right, dir=2 = back
  // teamColor = '#4488ff' (friendly) or '#ff4422' (enemy)
}
```

Each function is registered in `UNIT_MATS` via `makeDirTextures()` for both `_F` and `_E` variants.

## Visual Style per Unit

**Ghost** — slim armoured infantry, shorter than Marauder. Visor glow = team colour. Stealth suit dark grey. Sniper rifle visible in side/back views.

**Cyclone** — four-wheeled hover vehicle, squat profile. Lock-on launcher turret on top. Cockpit windshield = team colour glow. Engine vents on rear.

**Widow Mine** — compact burrowed-surface pose: three splayed legs, central targeting spike raised. Team colour on targeting sensor eye.

**Siege Tank** — tracked vehicle, long forward-facing barrel. Cockpit hatch visible on top. Team colour on cockpit glass + barrel tip glow.

**Thor** — large quad-legged walker mech. Dual shoulder cannons + twin arm autocannons. Core energy cell = team colour. Dominant silhouette — widest unit.

**Viking (air)** — sleek fighter jet silhouette, swept wings, twin engine nacelles. Cockpit = team colour glow. Rendered as fighter/air mode only.

**Raven** — hovering surveillance drone, disc/lozenge shape. Sensor array underside. Engine glow ring = team colour.

**Banshee** — twin-engine gunship, low-profile fuselage. Rotor/engine pods at wing tips. Cockpit = team colour glow.

**Liberator** — heavier bomber profile, wider than Banshee. Dual weapon pods. Engine wash glow = team colour.

**Battlecruiser** — capital ship, massive elongated hull. Multiple gun turrets. Yamato cannon port on bow. Running lights = team colour. Largest air unit — fills most of the 128×128 canvas.

## FLYING_UNITS Changes

```javascript
// Before
const FLYING_UNITS = new Set(['MEDIVAC', 'MUTALISK']);

// After
const FLYING_UNITS = new Set(['MEDIVAC', 'MUTALISK', 'VIKING', 'RAVEN', 'BANSHEE', 'LIBERATOR', 'BATTLECRUISER']);
```

## Showcase

`ShowcaseResource` gains a Terran row: 5 ground units at one tile band, 5 air units at a higher Y. All within Nexus sight range. Total seeded enemies rises from 10 to 20.

The Playwright assertion `showcaseRendersAllUnitsAboveTerrainSurface` is updated: expected enemy count 10 → 20.

## Tests

**Per unit (10 × smoke + spawn = 20 tests):**
- `smokeTestDrawFn('GHOST', 0, '#4488ff')` returns alpha > 0
- Ghost spawns at ground Y (`allEnemyWorldY()` values = `TERRAIN_SURFACE_Y + offset`)

**Flying elevation (5 tests):**
- Each air unit spawns at Y > ground unit Y (mirrors existing `mutaliskSpawnsHigherThanGroundUnit`)

**Showcase regression (1 test updated):**
- `showcaseRendersAllUnitsAboveTerrainSurface` — enemy count assertion 10 → 20

**Total new/modified tests:** ~26 (25 new + 1 updated assertion)

## Implementation Order

1. Add `FLYING_UNITS` entries first — no visual change, safe baseline
2. Ground infantry: Ghost
3. Ground vehicles: Cyclone, Widow Mine, Siege Tank, Thor (smallest → largest)
4. Air units: Viking, Raven, Banshee, Liberator, Battlecruiser (smallest → largest)
5. Register all 20 UNIT_MATS keys
6. Update ShowcaseResource — Terran row
7. Update + run Playwright tests

## GitHub

Create issue under epic #83 before implementation begins.
