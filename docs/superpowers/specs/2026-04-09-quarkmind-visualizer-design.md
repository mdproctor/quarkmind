# QuarkMind Visualizer — Design Spec
**Date:** 2026-04-09
**Supersedes:** *(none)*

---

## Context

QuarkMind has no visual feedback during development. The `%emulated` profile runs the
full agent loop against `EmulatedGame` but everything is log output. This spec covers a
live 2D visualizer — a PixiJS web app served by Quarkus, updated via WebSocket each
game tick, wrapped in an Electron app for native OS window experience.

The visualizer is QA/dev tooling. It is excluded from `%prod` via `@UnlessBuildProfile`.

---

## Architecture

```
┌─────────────────────────────────────────┐
│  Electron  electron/                    │
│  main.js — spawns Quarkus jar,          │
│  opens BrowserWindow → localhost:8080   │
└────────────────┬────────────────────────┘
                 │ HTTP + WebSocket
┌────────────────▼────────────────────────┐
│  Quarkus                                │
│  /visualizer.html  (PixiJS app)         │
│  /pixi.min.js      (bundled, no CDN)    │
│  /ws/gamestate     (WebSocket push)     │
│  /qa/sprites/{name} (image proxy)       │
└─────────────────────────────────────────┘
```

Works in any browser at `localhost:8080/visualizer.html`. Electron is a thin native
window wrapper — it adds nothing to the visualizer itself.

---

## Part 1: Quarkus Backend

### New dependency

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-websockets-next</artifactId>
</dependency>
```

### `GameStateBroadcaster`

Package: `io.quarkmind.qa`. Annotated `@ApplicationScoped`, `@UnlessBuildProfile("prod")`.

On startup (`@PostConstruct`): registers itself as a frame listener via
`sc2Engine.addFrameListener(this::onFrame)`.

`onFrame(GameState state)`: serialises `GameState` to JSON (using the project's existing JSON provider —
Jackson or JSON-B, whichever is on the classpath) and broadcasts the string to
all currently connected WebSocket sessions.

Holds a `Set<WebSocketConnection>` (thread-safe: `CopyOnWriteArraySet`) updated by
`GameStateSocket` via `addSession` / `removeSession`.

### `GameStateSocket`

Package: `io.quarkmind.qa`. Annotated `@WebSocket(path = "/ws/gamestate")`,
`@UnlessBuildProfile("prod")`.

```
@OnOpen   → broadcaster.addSession(connection)
@OnClose  → broadcaster.removeSession(connection)
```

No message handling needed in Phase 1 — this is server-push only.

### `SpriteProxyResource`

Package: `io.quarkmind.qa`. JAX-RS resource, `@UnlessBuildProfile("prod")`.

`GET /qa/sprites/{name}` — fetches the named image from Liquipedia, re-serves it with
the correct `Content-Type`, solves CORS for WebGL texture loading.

In-memory cache: `Map<String, byte[]>` keyed by filename. First request fetches from
Liquipedia; subsequent requests served from cache. Cache is never evicted (sprites are
static for the session).

Liquipedia base URL: `https://liquipedia.net/commons/images/`

Sprite filename → Liquipedia path mapping (hardcoded):

| Name | Liquipedia path |
|---|---|
| `SC2Probe.jpg` | `4/4f/SC2Probe.jpg` |
| `SC2Nexus.jpg` | `f/f8/SC2Nexus.jpg` |
| `SC2Pylon.jpg` | `4/48/SC2Pylon.jpg` |
| `SC2Gateway.jpg` | `9/99/SC2Gateway.jpg` |
| `SC2Zealot.jpg` | `5/5c/SC2Zealot.jpg` |
| `SC2Stalker.jpg` | `6/63/SC2Stalker.jpg` |

Unknown names → 404.

---

## Part 2: Visualizer Frontend

### Static resources

All files in `src/main/resources/META-INF/resources/` — served by Quarkus with zero
configuration. No build step. No npm.

```
src/main/resources/META-INF/resources/
  pixi.min.js        — PixiJS 8 minified, downloaded once
  visualizer.html    — entry point
  visualizer.js      — all app logic (~250 lines)
```

### `visualizer.html`

Minimal HTML shell: loads `pixi.min.js` and `visualizer.js`, provides a `<canvas id="game">`.
Dark background (`#1a1a2e`). No framework, no bundler.

### `visualizer.js`

**PixiJS application**
- `PIXI.Application` attached to `#game` canvas, 800×600px, background `#1a1a2e`
- `PIXI.Assets.load()` fetches all sprites from `/qa/sprites/` on startup before
  rendering begins

**Viewport**
- Tile range: (0,0) → (32,32), scale 20px/tile
- `tileToScreen(x, y)` → `{ x: x * SCALE, y: (32 - y) * SCALE }` (flip Y: game
  origin is bottom-left, canvas origin is top-left)

**Layers** (added to stage in order, bottom to top):

| Layer | Contents |
|---|---|
| `backgroundLayer` | Dark tiled grid (32×32, 1px lines, `#2a2a4e`) |
| `resourceLayer` | Geysers — green circle, 16px radius |
| `buildingLayer` | Nexus (64×64), Pylon (32×32), Gateway (48×48), etc. |
| `unitLayer` | Probes — portrait sprite, circular mask, 24px radius |
| `enemyLayer` | Enemy units — portrait sprite, red tint (`0xff4444`), 24px radius |
| `hudLayer` | Text overlay |

**Sprite rendering**
- `PIXI.Sprite.from(texture)` for each unit/building, positioned via `tileToScreen()`
- Circular mask: `PIXI.Graphics` circle applied as `sprite.mask`
- Buildings and geysers are stationary — updated only when `GameState` reports a change
- Units updated every message

**HUD** (`PIXI.Text`, top-left, white, 14px monospace):
```
Minerals: 234   Gas: 0   Supply: 12/15   Frame: 1042
```

**WebSocket client**
- Connects to `ws://${window.location.host}/ws/gamestate` on load
- On message: parse JSON → `updateScene(gameState)`
- On close: retry after 2s
- `updateScene()` reconciles current sprites against received state:
  - New entity → create and add sprite to layer
  - Existing entity → update position
  - Removed entity → destroy and remove from layer

**Fallback**: unknown `UnitType` or `BuildingType` → filled circle, colour by
allegiance (blue for friendly, red for enemy).

---

## Part 3: Electron Wrapper

Separate npm project at `electron/` in the repo root. Not a Maven module.

```
electron/
  package.json     — dependencies: electron, electron-builder
  main.js          — main process
  .gitignore       — node_modules/, dist/
```

### `package.json`

```json
{
  "name": "quarkmind-visualizer",
  "version": "1.0.0",
  "main": "main.js",
  "scripts": {
    "start": "DEV_MODE=1 electron .",
    "start:app": "electron .",
    "build": "electron-builder"
  },
  "dependencies": {
    "electron": "^34.0.0"
  },
  "devDependencies": {
    "electron-builder": "^25.0.0"
  }
}
```

### `main.js`

**Dev mode** (`DEV_MODE=1`): skips Quarkus spawn, opens
`http://localhost:8080/visualizer.html` immediately. Used during `mvn quarkus:dev`
development.

**App mode** (default): spawns
`java -jar ../target/quarkmind-agent-1.0.0-SNAPSHOT-runner.jar` as a child process,
polls `http://localhost:8080/q/health/live` every 500ms until HTTP 200 (max 30s
timeout), then opens window. On window close: kills the Quarkus subprocess.

Window: `BrowserWindow` 900×700, `autoHideMenuBar: true`, title "QuarkMind".

Quarkus stdout/stderr piped to `console.log`/`console.error` for visibility.

### Running

```bash
# Dev (Quarkus already running via mvn quarkus:dev -Dquarkus.profile=emulated)
cd electron && npm start

# Packaged (builds Quarkus uber-jar first)
mvn package -DskipTests
cd electron && npm run start:app
```

---

## Testing

### `GameStateBroadcasterTest` (plain JUnit)

- Create `GameStateBroadcaster` directly, add a mock `WebSocketConnection` that
  captures sent strings
- Call `onFrame(sampleGameState)`
- Assert captured string is valid JSON containing `minerals`, `myUnits`, `gameFrame`

### `SpriteProxyResourceTest` (`@QuarkusTest`)

- `GET /qa/sprites/SC2Probe.jpg` → 200, `Content-Type: image/jpeg`
- `GET /qa/sprites/unknown.jpg` → 404
- Second request for same name → served from cache (verify Liquipedia not called twice)

### Manual smoke test

1. `mvn quarkus:dev -Dquarkus.profile=emulated`
2. Open `http://localhost:8080/visualizer.html`
3. Verify: sprites load, nexus visible at centre of viewport, 12 probes arranged nearby,
   2 geysers at correct positions, minerals counter increments every ~500ms

---

## Sprite Inventory

Sprites needed at launch, all routed via `/qa/sprites/`:

| Entity | File | Notes |
|---|---|---|
| Probe | `SC2Probe.jpg` | Circular mask, 24px radius |
| Nexus | `SC2Nexus.jpg` | 64×64, no mask |
| Pylon | `SC2Pylon.jpg` | 32×32, no mask |
| Gateway | `SC2Gateway.jpg` | 48×48, no mask |
| Zealot | `SC2Zealot.jpg` | Circular mask, 24px, red tint |
| Stalker | `SC2Stalker.jpg` | Circular mask, 24px, red tint |

Geyser, Immortal, Colossus, etc. → coloured circle fallback until added to proxy map.

---

## Context Links

- Phase E1 design: `docs/superpowers/specs/2026-04-09-sc2-emulation-e1-design.md`
- SC2 image index: `docs/sc2-image-index.md`
- QA endpoints pattern: `src/main/java/io/quarkmind/qa/`
- GitHub: mdproctor/quarkmind
