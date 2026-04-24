# E23: Replay Playback + Interactive Unit Inspection

**Goal:** Animate units moving across real terrain during replay, with play/pause/scrub controls, click-to-inspect unit detail, and a dual camera mode toggle.

---

## Context

The visualizer already shows buildings appearing over time (from tracker events) and real terrain (from E22). Units appear at their spawn position but do not move — GAME_EVENTS are not yet parsed. This epic closes that gap.

---

## Architecture Overview

Four child issues in dependency order:

| Issue | What |
|---|---|
| E23a | `GameEventStream` + movement simulation |
| E23b | Replay controls (play/pause, rewind, scrub, speed) |
| E23c | Click-to-inspect panel |
| E23d | Camera mode toggle + full controls + UI scaling |

---

## E23a: GameEventStream + Movement Simulation

### GAME_EVENTS parsing

`GameEventStream` (pure Java, no CDI, `sc2/replay/` package) parses `CmdEvent` and `SelectionDeltaEvent` from the replay MPQ alongside tracker events. Scelight classes: `hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent`, `SelectionDeltaEvent`, `Delta`.

**Selection tracking:** `Map<Integer, List<String>>` — player userId → currently selected unit tags. `SelectionDeltaEvent.getDelta().getAddUnitTags()` adds tags; remove mask clears them.

**Order extraction:** When a `CmdEvent` arrives, the current selection for that userId receives the order:
- `getTargetPoint()` non-null → move order to (x, y)
- `getTargetUnit()` non-null → follow/attack that unit tag
- Both null → ability in place (harvest, cast) — ignored for movement

Output: `List<UnitOrder>` sorted by loop.

```java
public record UnitOrder(String unitTag, int loop, Float targetX, Float targetY, String targetUnitTag) {}
// targetX/Y non-null = move; targetUnitTag non-null = follow/attack; both null = stop
```

**Coordinate scale:** `CmdEvent.getTargetPoint().getXFloat()` / `getYFloat()` return SC2 tile coordinates directly — same scale as tracker event positions. No conversion needed.

### Position simulation

`UnitOrderTracker` (pure Java) holds the current active `UnitOrder` per unit tag and advances positions each tick:

```
for each unit with an active move order:
  advance position by (speed × LOOPS_PER_TICK / 22.4) toward target
  stop when within 0.5 tiles of target

for each unit with a follow order:
  advance toward current position of target unit (looked up from live unit map)
```

`ReplaySimulatedGame.tick()` calls `UnitOrderTracker.advance(currentLoop, unitPositions)` after processing tracker events. Tracker events still own birth/death — movement only updates position between those events.

**`Unit` record change:** add `targetPoint` field (nullable `Point2d` — null means stationary).

### Unit speed table (tiles/sec at Faster speed)

Added to `SC2Data` as `static final Map<UnitType, Double> UNIT_SPEEDS`:

| Unit | Speed | Unit | Speed |
|---|---|---|---|
| Probe | 3.94 | SCV | 3.94 |
| Zealot | 3.15 | Marine | 3.15 |
| Stalker | 4.13 | Marauder | 2.25 |
| Immortal | 3.15 | Medivac | 3.50 |
| Colossus | 2.77 | Ghost | 3.94 |
| Phoenix | 5.61 | Viking | 3.85 |
| Oracle | 5.61 | Drone | 2.95 |
| VoidRay | 3.50 | Zergling | 4.13 |
| Carrier | 1.97 | Roach | 3.15 |
| Tempest | 2.63 | Hydralisk | 3.15 |
| Mothership | 1.97 | Mutalisk | 5.61 |
| Disruptor | 3.15 | Baneling | 3.50 |
| Adept | 3.50 | Ultralisk | 4.13 |
| Archon | 3.94 | Overlord | 1.40 |
| *default* | 3.00 | Overseer | 3.94 |

### Integration with ReplayEngine

`ReplayEngine.connect()` constructs `GameEventStream` from the replay MPQ alongside `ReplaySimulatedGame`. Both share the same replay path; `GameEventStream` only requests `RepContent.GAME_EVENTS`.

### Testing

- `GameEventStreamTest` — parse Nothing_4720936.SC2Replay, assert order count > 1000, assert all target coordinates within map bounds (0–160, 0–208), assert at least one move order and one follow order.
- `UnitOrderTrackerTest` — feed a unit at (10,10) a move order to (20,20), advance 10 ticks, assert position advanced toward target at expected speed.
- `ReplaySimulatedGameTest` (integration) — run 200 ticks with game events, assert all unit positions within map bounds.

---

## E23b: Replay Controls

### Backend — new QA endpoints

```
GET  /qa/replay/status              → { loop, totalLoops, paused, speed }
POST /qa/replay/pause               → pauses scheduler tick
POST /qa/replay/resume              → resumes scheduler tick
POST /qa/replay/seek?loop=N         → reset + fast-forward to loop N, one broadcast at end
POST /qa/replay/speed?multiplier=2  → set tick rate (0.5, 1.0, 2.0, 4.0)
```

All endpoints annotated `@UnlessBuildProfile("prod")` and `@IfBuildProfile("replay")` (404 in non-replay profiles).

**Seek:** `ReplaySimulatedGame.seekTo(int targetLoop)` — calls `reset()`, tight-loops `tick()` to targetLoop with broadcasts suppressed, emits one `GameStateBroadcast`. For Nothing_4720936 (~11,200 loops) this takes <50ms.

**Speed:** A `@Scheduled` tick interval is runtime-adjustable via a volatile multiplier on `ReplayStartupBean`. The scheduler calls `engine.tick()` every `500ms / speed`.

**Total loops:** `ReplaySimulatedGame.totalLoops()` returns the loop of the last tracker event (read at parse time).

### Frontend — control bar

Fixed bar at bottom of viewport (44px, full width, visible only in replay profile):

```
[⏮]  [⏸/▶]  [══════●══════════════]  2:34 / 8:21  [½×] [1×] [2×] [4×]
```

- **⏮ Rewind** → `POST /qa/replay/pause` then `POST /qa/replay/seek?loop=0`
- **⏸/▶ Play/Pause** → toggle between `POST /qa/replay/pause` and `/resume`
- **Scrub bar** → `<input type="range">` from 0 to totalLoops; fires `POST /qa/replay/seek?loop=N` on `mouseup` (not during drag, to avoid request flood)
- **Time display** → polls `GET /qa/replay/status` every 500ms when playing; `loop / 22.4 / 60` for minutes
- **Speed buttons** → active one highlighted, fires `POST /qa/replay/speed?multiplier=X`

Control bar shown only when `GET /qa/current-map` returns a mapName (replay profile). Hidden otherwise.

### Testing

- `ReplayControlsIT` (`@QuarkusTest`, replay profile via test profile config): pause → assert status paused; seek 100 → assert loop ≈ 100; resume → wait 500ms → assert loop > 100.
- `ReplaySimulatedGameTest`: `seekTo(500)` completes in < 200ms.

---

## E23c: Click-to-Inspect Panel

### Raycasting

On left-click (only if drag delta < 5px, to avoid triggering on drag release):

```javascript
raycaster.setFromCamera(ndcMouse, camera);
const hits = raycaster.intersectObjects([...unitSpriteObjects.values()]);
if (hits.length > 0) showUnitPanel(hits[0].object.userData.tag);
```

Unit sprites store `{ tag, type, teamColor }` in `userData` at creation. `unitSpriteObjects` is a `Map<tag, Sprite>` already maintained for position updates.

### Panel design

Slides in from bottom-right on unit click; slides out on click-outside or ESC:

```
┌─────────────────────────┐
│  [64×64 portrait]  STALKER        │
│                    Friendly       │
│  HP  ████████░░  80 / 100         │
│  SH  ██████████  80 / 80          │
│  Moving → (82, 104)               │
└───────────────────────────────────┘
```

- **Portrait**: `smokeTestDrawFn(type, dir=0, teamColor)` on a 64×64 canvas — reuses existing draw functions
- **Health bar**: green (>50%) → yellow (25–50%) → red (<25%)
- **Shield bar**: blue, Protoss units only
- **Current order**: "Moving → (82, 104)" or "Attacking tag #4A2" from `UnitOrderTracker`

CSS: `position: fixed; bottom: 56px; right: 12px; width: 220px; transform: translateX(240px)` (hidden). On show: `transform: translateX(0)` with 150ms ease transition.

### New endpoint

```
GET /qa/unit/{tag}  → { tag, type, health, maxHealth, shields, maxShields, orderDescription }
```

Reads from the current `SC2Engine.observe()` snapshot. `orderDescription` is a human-readable string produced by `UnitOrderTracker`.

### Testing

- Playwright: POST showcase to seed units in mock profile, click canvas at a known unit world-position (converted via `worldToScreen`), assert panel element becomes visible and contains the correct unit type name.
- Unit: `UnitResourceTest` — inject SimulatedGame with a known unit, GET /qa/unit/{tag}, assert correct type and health.

---

## E23d: Camera Mode Toggle + Full Controls + UI Scaling

### Two modes

Toggle button in top-right HUD: `[🎮 SC2] [🔭 3D]`. State persists in `localStorage('quarkmind.cameraMode')`. Default: SC2 mode.

**Shared controls (both modes):**
| Control | Action |
|---|---|
| Scroll wheel | Zoom in/out |
| WASD / arrow keys | Pan camera |
| Left-click on unit | Select (opens inspect panel) |
| Left-click on empty | Deselect, close panel |
| Mouse to window edge (within 20px) | Pan (edge scroll) |

**SC2 mode only:**
| Control | Action |
|---|---|
| Left-drag | Pan (no orbit) |
| Camera pitch | Locked at 55° above horizon |

**3D Explore mode only:**
| Control | Action |
|---|---|
| Left-drag | Orbit (change theta/phi, unlocked) |
| Right-drag | Pan |

### Implementation

`setupCamera()` checks `cameraMode` flag on each `mousedown`. WASD/arrow `keydown` listener added once. Edge scroll on `mousemove` checks `e.clientX < 20`, `> window.innerWidth - 20`, etc. Pan speed scales with `camDist * 0.015` so it feels consistent at any zoom level.

### UI scaling

All overlay elements use fixed viewport-relative positioning:
- **HUD** (minerals/supply): `position: fixed; top: 12px; right: 12px`
- **Control bar**: `position: fixed; bottom: 0; left: 0; width: 100%; height: 44px`
- **Inspect panel**: `position: fixed; bottom: 56px; right: 12px; width: 220px`
- **Mode toggle**: `position: fixed; top: 12px; left: 12px`
- **Canvas**: `width: 100vw; height: 100vh; display: block`

### Testing

- Playwright: assert canvas fills 1280×720 viewport (no scrollbar, no overflow).
- Playwright: assert WASD key moves camera (camTarget changes after keydown event).
- Manual: verify left-drag orbits in 3D mode and pans in SC2 mode; verify mode toggle persists after reload.

---

## File Map

**New Java:**
- `src/main/java/io/quarkmind/sc2/replay/GameEventStream.java`
- `src/main/java/io/quarkmind/sc2/replay/UnitOrder.java`
- `src/main/java/io/quarkmind/sc2/replay/UnitOrderTracker.java`
- `src/main/java/io/quarkmind/qa/ReplayControlsResource.java`
- `src/main/java/io/quarkmind/qa/UnitResource.java`
- `src/test/java/io/quarkmind/sc2/replay/GameEventStreamTest.java`
- `src/test/java/io/quarkmind/sc2/replay/UnitOrderTrackerTest.java`
- `src/test/java/io/quarkmind/qa/ReplayControlsIT.java`

**Modified Java:**
- `src/main/java/io/quarkmind/domain/Unit.java` — add nullable `targetPoint` field
- `src/main/java/io/quarkmind/domain/SC2Data.java` — add `UNIT_SPEEDS` map
- `src/main/java/io/quarkmind/sc2/mock/ReplaySimulatedGame.java` — integrate `UnitOrderTracker`, add `seekTo()`, `totalLoops()`
- `src/main/java/io/quarkmind/sc2/replay/ReplayEngine.java` — construct `GameEventStream`, expose pause/resume/seek/speed
- `src/main/java/io/quarkmind/sc2/replay/ReplayStartupBean.java` — adjustable tick rate

**Modified frontend:**
- `src/main/resources/META-INF/resources/visualizer.js` — unit position updates, control bar, inspect panel, camera mode toggle, full controls

---

## Dependency Order

```
E23a  →  E23b  (seek needs UnitOrderTracker reset to be clean)
E23a  →  E23c  (panel needs UnitOrderTracker for current order description)
E23d can be developed in parallel with E23b and E23c
```

---

## Spec Self-Review

- No TBDs or incomplete sections.
- Coordinate scale confirmed: `CmdEvent.getTargetPoint().getXFloat()` returns tile coordinates matching tracker event scale — verified by probe (probe move to (62.5, 21.3) is within Torches AIE's 160×208 bounds).
- `Unit.targetPoint` is nullable — no change to existing unit construction, just an optional field.
- Control bar height (44px) accounts for the inspect panel bottom offset (56px = 44px bar + 12px gap).
- Edge scroll intentionally not enabled by default (a checkbox in the HUD can toggle it) — avoids accidental panning when using browser devtools at the edge.
