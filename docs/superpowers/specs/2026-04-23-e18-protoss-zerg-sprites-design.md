# E18 Design: Remaining Protoss + Zerg Sprites

**Date:** 2026-04-23
**Episode:** E18
**Parent epic:** #83 (3D Visualizer)

## Goal

Complete 2D sprite coverage for all 11 remaining Protoss units and all 9 remaining Zerg units (20 total), bringing full sprite coverage to 41/43 unit types (UNKNOWN fallback remains). Each unit gets a canvas 2D draw function following the established pattern (4 directions, 2 team colours, 128×128 px textures).

## Units in Scope

### Protoss (11)

| Unit | Category | UNIT_MATS keys | Flying | Visual concept |
|------|----------|----------------|--------|----------------|
| Immortal | Ground | `IMMORTAL_F`, `IMMORTAL_E` | No | Heavy walker, thick shield plates, assault cannon arm, team colour on cannon energy cell |
| Colossus | Ground | `COLOSSUS_F`, `COLOSSUS_E` | No | Massive 4-legged walker, very wide stance, twin thermal lances on top, team colour on lance tips |
| Dark Templar | Ground | `DARK_TEMPLAR_F`, `DARK_TEMPLAR_E` | No | Slim cloaked warrior, curved warp blade, dark body with team colour on blade edge and eyes |
| High Templar | Ground | `HIGH_TEMPLAR_F`, `HIGH_TEMPLAR_E` | No | Robed psion, psionic energy swirling around hands in team colour, staff |
| Archon | Ground | `ARCHON_F`, `ARCHON_E` | No | No solid body — double ring of energy arcs, glowing core in team colour |
| Adept | Ground | `ADEPT_F`, `ADEPT_E` | No | Streamlined warrior, psi-lance blade, team colour on blade and eye strip |
| Disruptor | Ground | `DISRUPTOR_F`, `DISRUPTOR_E` | No | Floating sphere, large energy buildup glow in team colour |
| Sentry | Ground | `SENTRY_F`, `SENTRY_E` | No | Small hovering guardian, shield projector hub, spinning ring, team colour on emitters |
| Observer | **Air** | `OBSERVER_F`, `OBSERVER_E` | Yes | Small cloaking disc, mostly dark outline, sensor arrays, team colour on scan emitters |
| Void Ray | **Air** | `VOID_RAY_F`, `VOID_RAY_E` | Yes | Large angular warship, pointed bow, prismatic beam emitter in team colour |
| Carrier | **Air** | `CARRIER_F`, `CARRIER_E` | Yes | Large Protoss capital ship, hangar bay with interceptor silhouettes, team colour running lights |

### Zerg (9)

| Unit | Category | UNIT_MATS keys | Flying | Visual concept |
|------|----------|----------------|--------|----------------|
| Ultralisk | Ground | `ULTRALISK_F`, `ULTRALISK_E` | No | Massive tank unit, huge Kaiser blade scythes on shoulders, armoured carapace, team colour on bio-sacs |
| Infestor | Ground | `INFESTOR_F`, `INFESTOR_E` | No | Fungal organism, rounded body, tentacle protrusions, team colour on infested glow |
| Swarm Host | Ground | `SWARM_HOST_F`, `SWARM_HOST_E` | No | Large armoured beetle, carapace with locust-spawn chambers visible, team colour on spawn vents |
| Queen | Ground | `QUEEN_F`, `QUEEN_E` | No | Tall Zerg queen, wing appendages folded, tentacle arms, team colour on bio-sacs |
| Ravager | Ground | `RAVAGER_F`, `RAVAGER_E` | No | Evolved Roach, larger body, organic bile cannon raised on back, team colour on cannon and bio-sacs |
| Lurker | Ground | `LURKER_F`, `LURKER_E` | No | Burrowed surface pose — spiny spikes and ground mound, team colour on spine tips |
| Brood Lord | **Air** | `BROOD_LORD_F`, `BROOD_LORD_E` | Yes | Large flying unit, massive wings, hanging broodling sacs below, team colour on bio-sacs |
| Corruptor | **Air** | `CORRUPTOR_F`, `CORRUPTOR_E` | Yes | Flying spore ball, tentacle clusters, corruption spray, team colour on spore bioluminescence |
| Viper | **Air** | `VIPER_F`, `VIPER_E` | Yes | Flying serpent, elongated eel body, abduct claw at front in team colour |

## Design Decisions

**Archon:** No solid body. Rendered as two overlapping energy rings with arc discharges and a bright core. Team colour is the dominant colour — Archon is defined by the energy colour. All 4 directions are nearly identical (slight asymmetry only).

**Colossus:** Widest ground sprite. 4 long legs in wide stance. In side view (dir=1), the extreme height and forward lean show clearly. In front/back view, legs spread left-right to fill the canvas.

**Lurker:** Always burrowed in lurker mode. Show 5–6 spines erupting from a ground mound. Similar to Widow Mine but organic/irregular. Team colour on spine tips.

**Disruptor:** Floating sphere treated as ground unit (hovers at ground level). Rendered as a charged orb with energy arcing across the surface.

**Sentry/Observer:** Both small hovering units. Sentry at ground level, Observer is flying (added to FLYING_UNITS).

**Viper:** Flying Zerg caster in SC2 — added to FLYING_UNITS.

**Queen:** Ground unit in SC2 despite having wings — not added to FLYING_UNITS.

## FLYING_UNITS Changes

```javascript
// Before
const FLYING_UNITS = new Set(['MEDIVAC', 'MUTALISK', 'VIKING', 'RAVEN', 'BANSHEE', 'LIBERATOR', 'BATTLECRUISER']);

// After
const FLYING_UNITS = new Set([
  'MEDIVAC', 'MUTALISK',
  'VIKING', 'RAVEN', 'BANSHEE', 'LIBERATOR', 'BATTLECRUISER',
  'OBSERVER', 'VOID_RAY', 'CARRIER',
  'BROOD_LORD', 'CORRUPTOR', 'VIPER'
]);
```

## Showcase

**No change.** ShowcaseResource stays at 20 enemies. Individual smoke+spawn tests verify each new unit. Extending the showcase to 40 units would require complex position geometry within the 8.5-tile Nexus sight-range constraint and risks breaking the bounds assertion.

## Tests

**Per unit (20 × 2 = 40 tests):**
- Smoke test: `smokeTestDrawFn('drawX', dir, color)` returns alpha > 0 for all 8 dir/team combos
- Spawn test: unit renders in browser (enemyCount == 1)

**Elevation tests (6 air units × 1 = 6 tests):**
- Observer, Void Ray, Carrier, Brood Lord, Corruptor, Viper each spawn higher than a ground Marine (Y > marineY + 0.3)

**Total new tests: 46**

**smokeTestDrawFn lookup table:** All 20 new draw functions must be added to the manual lookup table at lines ~98–130 of `visualizer.js`.

## Implementation Order

1. Create GitHub issue under epic #83
2. Protoss ground units (8): Sentry, Adept, Dark Templar, High Templar, Disruptor, Immortal, Archon, Colossus
3. Protoss air units (3) + FLYING_UNITS additions: Observer, Void Ray, Carrier
4. Zerg ground units (6): Ravager, Infestor, Lurker, Swarm Host, Queen, Ultralisk
5. Zerg air units (3) + FLYING_UNITS additions: Corruptor, Viper, Brood Lord
6. Run full Playwright suite + close issue
