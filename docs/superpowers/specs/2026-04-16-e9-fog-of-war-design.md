# E9: Fog of War — Design Spec

**Date:** 2026-04-16
**Scope:** Emulated profile only. Mock, replay, and real SC2 modes unaffected.

---

## Overview

Adds authentic SC2 fog of war to the emulated game. Enemy units are hidden server-side when outside friendly vision — the agent's `GameState` contains only genuinely visible enemies, making scouting and the `DroolsScoutingTask` meaningfully load-bearing. The visualizer renders a three-state fog overlay (unseen / memory / visible) for observers.

---

## Visibility Model

### TileVisibility enum

```
UNSEEN   — tile never entered any friendly unit's vision radius
MEMORY   — tile was visible at some point; terrain remembered, no units shown
VISIBLE  — tile is currently within a friendly observer's vision radius
```

### VisibilityGrid

New class `io.quarkmind.sc2.emulated.VisibilityGrid` — a 64×64 grid of `TileVisibility`, persisted across ticks (MEMORY accumulates; never reverts to UNSEEN). Lives in the emulated package, not `domain/`, because it is specific to the emulated engine.

```java
public class VisibilityGrid {
    private final TileVisibility[][] tiles; // [x][y]

    public void recompute(List<Unit> friendly, List<Building> buildings, TerrainGrid terrain);
    public TileVisibility at(int x, int y);
    public boolean isVisible(Point2d pos); // rounds to tile
    public void reset();                   // called on game restart
}
```

**Tick algorithm:**
1. For every tile currently VISIBLE, demote to MEMORY.
2. For each friendly unit and building, compute vision tiles (circle within sight range).
3. For each tile in the circle, apply height rule (see below). If passes: mark VISIBLE.

MEMORY tiles are never re-shown as having units on them — only terrain.

If `terrainGrid` is null (emulated game started without terrain configured), `recompute()` treats all tiles as LOW — no height-based blocking, full circular vision for all observers.

---

## Sight Ranges (official SC2 values)

Only friendly unit and building types need entries — we compute friendly vision only.

| Type | Sight range (tiles) |
|---|---|
| Probe | 8 |
| Zealot | 9 |
| Stalker | 10 |
| Nexus | 9 |
| Gateway | 9 |
| Assimilator | 6 |

Added to `SC2Data` as `sightRange(UnitType)` and `sightRange(BuildingType)`.

---

## High Ground Visibility Rule (official SC2)

Observer height determines which tiles they can illuminate:

- **Observer on LOW or RAMP** → vision circle covers LOW and RAMP tiles only. HIGH tiles are never marked VISIBLE regardless of range.
- **Observer on HIGH** → vision circle covers HIGH, RAMP, and LOW tiles normally.

Consequence: the entire enemy staging area (HIGH ground, y > 18) is invisible to LOW-ground units. A probe must reach HIGH ground to reveal it. This makes `DroolsScoutingTask`'s active scouting genuinely meaningful — the scout's vision is the only source of HIGH-ground intelligence.

WALL tiles are never marked VISIBLE (units cannot stand on them).

---

## Server-Side Filtering

### EmulatedGame changes

`EmulatedGame` gains a `VisibilityGrid` field, recomputed each tick inside `tick()` after friendly units move:

```java
private final VisibilityGrid visibility = new VisibilityGrid();

// Inside tick(), after moveUnits():
visibility.recompute(myUnits, myBuildings, terrainGrid);

// observe() filters before returning:
List<Unit> visibleEnemies = enemyUnits.stream()
    .filter(u -> visibility.isVisible(u.position()))
    .toList();
List<Unit> visibleStaging = enemyStagingArea.stream()
    .filter(u -> visibility.isVisible(u.position()))
    .toList();
```

`reset()` calls `visibility.reset()`.

### VisibilityHolder (CDI bridge)

`SC2Engine` interface stays clean — no visibility concept added to it.

`EmulatedEngine` updates a CDI `@ApplicationScoped` `VisibilityHolder` after each tick. The holder is null-safe in other engine modes.

```java
@ApplicationScoped
public class VisibilityHolder {
    private volatile VisibilityGrid current; // null when not in emulated mode
    public void set(VisibilityGrid g) { current = g; }
    public VisibilityGrid get() { return current; }
}
```

### GameStateBroadcast (new broadcast record)

`GameStateBroadcaster` currently sends raw `GameState`. It becomes:

```java
record GameStateBroadcast(GameState state, String visibility)
```

`visibility` is a flat 4096-character string (`'0'`=UNSEEN, `'1'`=MEMORY, `'2'`=VISIBLE), encoded as `tiles[y*64+x]`. Null when not in emulated mode — visualizer skips fog rendering if absent.

Compact string chosen over `int[][]` JSON (~4 KB vs ~12 KB per frame at 22.4 fps).

---

## Visualizer Changes

### Fog layer

A single `PIXI.Graphics` object (`fogLayer`) is added above the terrain layer but below the units layer. Redrawn each frame in a single batched pass:

```js
fogLayer.clear();
for (let y = 0; y < 64; y++) {
  for (let x = 0; x < 64; x++) {
    const s = visibility.charAt(y * 64 + x);
    if (s === '2') continue;                          // VISIBLE — no overlay
    const alpha = s === '0' ? 1.0 : 0.45;            // UNSEEN=solid, MEMORY=45%
    fogLayer.rect(x * TILE, yFlip(y) * TILE, TILE, TILE)
            .fill({ color: 0x000000, alpha });
  }
}
```

**Three visual states:**
- **UNSEEN (0)** — solid black (`alpha 1.0`)
- **MEMORY (1)** — 45% black overlay; terrain colour visible beneath, no units
- **VISIBLE (2)** — no overlay; full colour, units shown

45% opacity chosen (over 75%) for readability — terrain type and height shading remain legible for observers watching the agent play.

### Backward compatibility

When `visibility` is absent from the broadcast payload (mock / replay / real SC2 modes), `fogLayer` is not drawn. Enemy units continue to render at full fidelity as before.

### WebSocket parsing change

The visualizer currently deserialises `GameState` directly. It now reads `GameStateBroadcast`:

```js
const { state, visibility } = JSON.parse(event.data);
// use state as before; use visibility string for fog layer
```

---

## What Does Not Change

- `SC2Engine` interface — no modifications
- `SimulatedGame` (mock mode) — no fog, no VisibilityGrid
- `ReplayEngine` — no fog; observe-only mode exposes all units
- `RealSC2Engine` — SC2 handles fog natively; `GameState` already contains only visible units; `VisibilityHolder` remains null
- `DroolsScoutingTask` — no changes; it now receives a genuinely limited `enemyUnits` list, making its active scout and CEP rules load-bearing
- `GameState` domain record — unchanged; filtering happens before construction in `observe()`

---

## Testing

### Unit tests (plain JUnit, no CDI)

- `VisibilityGridTest` — recompute with known unit positions and terrain; assert VISIBLE/MEMORY/UNSEEN per tile
- High ground rule: observer on LOW → HIGH tiles stay UNSEEN even within sight range
- High ground rule: observer on HIGH → HIGH tiles become VISIBLE
- MEMORY accumulation: tile stays MEMORY after observer moves away
- `reset()` clears all tiles to UNSEEN

### Integration tests (`@QuarkusTest`)

- `EmulatedGameFogIT` — after tick with friendly units on LOW, `observe().enemyUnits()` contains no HIGH-ground enemies; after probe moved to HIGH, HIGH-ground enemies appear
- `GameStateBroadcasterFogIT` (or extend existing) — WebSocket payload includes `visibility` string of length 4096 in emulated mode; null in mock mode
- `GameStateWebSocketTest` — currently asserts on raw `GameState` JSON fields; must be updated to unwrap the new `GameStateBroadcast` envelope (`state` field) before asserting

### Visualizer tests (`@Tag("browser")`, Playwright)

- `VisualizerFogRenderTest` — fog layer present; tile at known UNSEEN position is black; tile at known VISIBLE position shows terrain colour; tile at MEMORY position shows dim terrain

---

## Constraints

- `VisibilityGrid` lives in `sc2/emulated/` — do not reference it from `domain/` or `sc2/` interfaces
- `VisibilityHolder` is a CDI bridge; keep it thin (getter/setter only)
- NATIVE.md: `VisibilityGrid` uses plain Java arrays — native-safe, no entry needed
