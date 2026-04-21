# E14 Design — 3D Visualizer + Protoss Cartoon Sprites

**Date:** 2026-04-21  
**Status:** Approved

## Goal

Replace the current PixiJS 2D visualizer with a Three.js 3D renderer. The server-side is untouched — same WebSocket `GameStateBroadcast` protocol, same `GameState` domain model. Only `visualizer.js` and `visualizer.html` change.

Deliver:
- 3D terrain with orbiting camera
- Fog of war on the 3D terrain
- Directional 2D cartoon sprites for Protoss units (Probe, Zealot, Stalker)
- Configurable toggle: 2D sprites ↔ 3D sphere models

Later epics add Terran (E15), Zerg (E16), combat indicators (E17), and replay controls (E18).

---

## Architecture

### Server side — unchanged

`GameStateBroadcaster` continues to push `GameStateBroadcast(GameState state, String visibility)` via WebSocket at `/ws/gamestate`. No protocol changes.

### Client side — Three.js replaces PixiJS

`visualizer.js` is replaced entirely. Three.js is loaded as a local static asset (`sprites/three.min.js`) — no CDN dependency.

```
visualizer.html
  └── visualizer.js (Three.js renderer)
       ├── Terrain layer       — BoxGeometry tiles
       ├── Fog layer           — PlaneGeometry overlays, renderOrder=5
       ├── Building layer      — BoxGeometry (E14)
       ├── Unit layer 2D       — THREE.Sprite with directional canvas textures
       ├── Unit layer 3D       — THREE.Group sphere+eyes geometry (toggle)
       └── HUD                 — HTML overlay (not WebGL)
```

---

## Terrain

Each tile is a `BoxGeometry(TILE×0.98, height, TILE×0.98)` placed on the XZ plane:

| Tile type | Height | Y position |
|---|---|---|
| Ground | 0.1 | 0.05 |
| Wall | TILE×1.2 | TILE×0.6 |
| Ramp | 0.3 | 0.15 |

Grid lines are `LineSegments` (EdgesGeometry) at y=0.07.

Minerals: small `BoxGeometry(0.5, 0.4, 0.5)` with emissive glow.

**Lighting:** `AmbientLight(0x223355, 0.9)` + `DirectionalLight(0xaabbff, 1.3)` at (20, 40, 20) with shadow mapping (2048×2048 shadow map). Fill light from (-10, 20, -10).

Tile size: `TILE = 1.4` world units. Grid: 20×20. `HALF = GRID × TILE / 2` used to centre at origin.

---

## Camera

Spherical orbit camera. State: `(camTheta, camPhi, camDist, camTarget)`. Smooth lerp to target values each frame (`factor = 0.1`).

Controls:
- **Left drag** — orbit (theta, phi)
- **Scroll** — zoom (camDist, clamped 6–55)
- **Right drag** — pan (camTarget)

Angle presets:

| Preset | phi | dist |
|---|---|---|
| Top-down | 0.12 | 32 |
| Isometric | π/3.5 | 24 |
| Low angle | π/2.3 | 20 |

---

## Fog of War

One `PlaneGeometry(TILE×0.98, TILE×0.98)` per tile, rotated flat (rotation.x = -π/2), positioned at y=0.18. Material: `MeshBasicMaterial(black, transparent, depthWrite:false)`. `renderOrder = 5` (renders after all units).

Per-frame update driven by the `visibility` string from `GameStateBroadcast`:
- `'0'` UNSEEN → `opacity = 1.0`
- `'1'` MEMORY → `opacity = 0.45`
- `'2'` VISIBLE → `mesh.visible = false`

`renderOrder = 5` ensures fog planes render after sprites and 3D models, so they correctly test against the depth buffer written by units.

---

## Unit Rendering

### 2D Sprites (default)

Each unit type has **4 pre-drawn canvas textures** (front, right, back, left), generated at startup via `Canvas2D` and loaded as `THREE.CanvasTexture`.

Each unit is a `THREE.Sprite`. Per frame:
1. Compute `getDir4(unit.facing, unit.worldPos, camera.position)` → 0–3
2. Swap `sprite.material` to the matching directional texture

**Direction calculation:**
```javascript
function getDir4(facingAngle, unitPos, camPos) {
  const camAngle = Math.atan2(-(camPos.x - unitPos.x), camPos.z - unitPos.z);
  let rel = camAngle - facingAngle;
  while (rel < 0) rel += Math.PI * 2;
  while (rel >= Math.PI * 2) rel -= Math.PI * 2;
  return Math.round(rel / (Math.PI / 2)) % 4; // 0=front,1=right,2=back,3=left
}
```

Note the negated `dx` — required to match Three.js screen-space handedness.

**Unit facing direction** is derived from the position delta between the current and previous `GameState` tick. A unit with no movement delta retains its last facing direction. Initial default (first tick, no prior position): south (toward the isometric camera starting position).

**Critical material settings:**
```javascript
new THREE.SpriteMaterial({
  map: texture, transparent: true,
  depthWrite: true,   // sprites write depth so fog can't bleed through
  alphaTest: 0.1      // transparent sprite borders skip depth write
});
```

Without `depthWrite: true` + `alphaTest`, fog planes (renderOrder=5) render through sprites at low camera angles.

**Side-view flip rule:** `const flip = dir===3 ? -1 : 1` — places eye and arm at the front end of the profile.

### 3D Models (toggle)

Sphere body (`SphereGeometry(0.38, 20, 14)`) + two white eye spheres + black pupil spheres. Enemy units additionally have 5 cone spikes. Both groups (`group2d`, `group3d`) live in the scene; toggle switches `visible`.

---

## Protoss Unit Art — E14 Scope

12 canvas textures (4 directions × 3 units), drawn at startup:

| Unit | Colour | Key detail |
|---|---|---|
| Probe | Blue (#4488dd) | Energy ball arm on leading side |
| Zealot | Purple (#7755cc) | Cyan blade on leading side; back shows blade tips |
| Stalker | Dark grey | 4 legs; single glowing blue eye; cannon on top |

**Direction art rules:**
- **Front:** full face, two eyes, weapon visible
- **Side:** horizontal oval profile; single eye at `cx + flip*S*0.16`; weapon extends from leading end
- **Back:** no eyes; crest/antenna/spine detail; weapon partially visible from behind

Buildings (Nexus, Pylon, Gateway) are `BoxGeometry` for E14. Cartoon building sprites deferred to a later pass.

---

## HUD

HTML `div` overlay (not WebGL canvas), positioned absolutely:

```
Minerals: NNN | Gas: NNN | Supply: NN/NN | Frame: NNNN
```

Updated from `GameState` fields each WebSocket message.

---

## Static Assets

```
src/main/resources/META-INF/resources/
  visualizer.html         — updated (Three.js, new canvas structure)
  visualizer.js           — replaced
  sprites/
    three.min.js           — Three.js r128 (local, no CDN)
    (sprite PNGs deferred — canvas-generated at runtime for E14)
```

---

## QA Endpoints — Unchanged

`/qa/emulated/config`, `/qa/emulated/terrain`, `/sc2/start`, `/sc2/stop` all unchanged. The `EmulatedConfigResource` config panel is re-implemented in the new visualizer (profile-conditional, same as before).

---

## Out of Scope for E14

- Terran and Zerg unit art (E15, E16)
- Walk-cycle, attack, and death animations
- Combat indicators / attack lines (E17)
- Replay pause/step/seek controls (E18)
- Cartoon building sprites

---

## Testing

Existing `VisualizerRenderTest` and `VisualizerFogRenderTest` are updated:
- Assert canvas is present and non-empty (Three.js renders to `<canvas>`)
- Assert HUD text contains minerals value
- Assert fog overlay is applied (pixel-sample a known UNSEEN tile)
- Assert sprite count matches unit count from `GameState`

`GameStateWebSocketTest` and `GameStateBroadcasterTest` are unaffected (server-side only).
