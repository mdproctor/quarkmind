# E22: Replay Mode with Real Map Terrain

**Goal:** Watch a real SC2 replay in the 3D visualizer with accurate map terrain — walls, high ground, and ramps from the actual .SC2Map file.

---

## Terrain Format (Discovered)

`.SC2Map` files are MPQ archives. Terrain lives in two files:

**`t3SyncCliffLevel`** (magic `CLIF`, 16-byte header, 2 bytes/cell, little-endian):
- Header: `CLIF` (4) + version (4) + width (4) + height (4)
- One `uint16` per cell in row-major order
- Cliff level encoding (multiples of 64 are discrete tiers; non-multiples are ramps):
  - `0` → void (out of bounds) → **WALL**
  - `64` → lowest walkable tier → **LOW**
  - `72–112` → transition between low and mid tiers → **RAMP**
  - `128` → mid tier (center/neutral ground) → **HIGH**
  - `136–176` → transition between mid and main base → **RAMP**
  - `192` → main base tier (player starts) → **HIGH**
  - `256` → elevated patch → **HIGH**
  - `320` → impassable border cliff → **WALL**
- Rule: `cliffLevel == 0 || cliffLevel >= 320` → WALL; `cliffLevel % 64 != 0` → RAMP; `cliffLevel == 64` → LOW; else → HIGH

Torches AIE (160×208): void=5874, low(64)=7568, mid(128)=7664, base(192)=6774, border(320)=4800.

**Unit coordinates** from tracker events map directly to terrain grid tiles (verified against Torches AIE).

---

## Map Acquisition

AI Arena season maps are downloadable from:
`https://aiarena.net/wiki/184/plugin/attachments/download/{id}/` (302 → signed S3 URL, zip)

Known season packs:
| Attachment | Maps |
|---|---|
| 45 (2025 PreSeason 2) | TorchesAIE_v4, IncorporealAIE_v4, LeyLinesAIE_v3, PersephoneAIE_v4, PylonAIE_v4 |
| 44 (2025 Season 2) | MagannathaAIE, UltraloveAIE, + 6 others |

**Name fuzzy match:** replay filename `MagannathaAIE_v2.SC2Map` → strip `_v\d+` → match `MagannathaAIE.SC2Map` in pack.

Maps cache locally at `~/.quarkmind/maps/<filename>.SC2Map`. Terrain JSON caches at `~/.quarkmind/maps/<basename>-terrain.json`.

---

## Architecture

### New: `sc2/map/` package

**`SC2MapTerrainExtractor`** — pure Java, no CDI:
```
extract(Path sc2map, int width, int height) → TerrainGrid
```
Opens the .SC2Map MPQ, reads `t3SyncCliffLevel`, applies cliff→TerrainGrid rules above.

**`MapDownloader`** — CDI bean:
```
download(String mapFileName) → Path (to .SC2Map in local cache)
```
1. Check local cache first
2. Look up attachment ID from bundled `map-registry.json`
3. HTTP GET with redirect follow → unzip → save .SC2Map
4. Fuzzy name match (strip version suffix) if exact name not found in any pack

**`SC2MapCache`** — CDI bean, ties it together:
```
getTerrainResponse(String mapFileName, int width, int height) → TerrainResponse
```
Checks JSON cache → if miss, calls `MapDownloader.download()` + `SC2MapTerrainExtractor.extract()` → serialises to JSON cache.

### New: `qa/TerrainResource`

```
GET /qa/terrain?map=TorchesAIE_v4
```
- Available in all non-prod profiles (`@UnlessBuildProfile("prod")`)
- Returns `TerrainResponse` (same record as `EmulatedTerrainResource` — reuse)
- Triggers download + extraction on first call per map
- Returns 404 if map not found after download attempt

### New: `qa/CurrentMapResource`

```
GET /qa/current-map → { "mapName": "TorchesAIE_v4", "mapWidth": 160, "mapHeight": 208 }
```
- Returns 404 in non-replay profiles
- `ReplayEngine` populates from `replay.gamemetadata.json` (parsed via `MpqParser` on `connect()`)

### Modified: `SC2Engine` interface

Add:
```java
default String getMapName() { return null; }
default int getMapWidth()   { return 0; }
default int getMapHeight()  { return 0; }
```
`ReplayEngine` overrides all three. `EmulatedEngine` / mock engines use defaults.

### Modified: `ReplayEngine`

On `connect()`:
1. Parse `replay.gamemetadata.json` from replay MPQ (already can open MPQ via `MpqParser`)
2. Extract `MapName` (e.g., `TorchesAIE_v4.SC2Map`)
3. Parse `GameDescription` from `INIT_DATA` for `mapSizeX` / `mapSizeY` (already done in `ReplaySimulatedGame`)
4. Store both; expose via `getMapName()`, `getMapWidth()`, `getMapHeight()`

### Modified: Visualizer

Startup sequence for terrain loading:
```javascript
async function loadTerrain() {
  // 1. Try replay profile: GET /qa/current-map
  const mapMeta = await tryFetch('/qa/current-map');
  if (mapMeta) {
    GRID_W = mapMeta.mapWidth; GRID_H = mapMeta.mapHeight;
    const terrain = await fetch(`/qa/terrain?map=${encodeURIComponent(mapMeta.mapName)}`);
    if (terrain.ok) { /* render terrain tiles, set hasRealTerrain=true */ return; }
  }
  // 2. Try emulated profile: GET /qa/emulated/terrain
  const emTerrain = await tryFetch('/qa/emulated/terrain');
  if (emTerrain) { /* existing emulated terrain logic */ return; }
  // 3. Fallback: flat 64×64 mock terrain
}
```

Camera distance already adjusts for map size when `hasRealTerrain=true`.

---

## Bundled `map-registry.json`

`src/main/resources/map-registry.json`:
```json
{
  "baseUrl": "https://aiarena.net/wiki/184/plugin/attachments/download/",
  "packs": [
    { "id": 45, "maps": ["TorchesAIE_v4", "IncorporealAIE_v4", "LeyLinesAIE_v3", "PersephoneAIE_v4", "PylonAIE_v4"] },
    { "id": 44, "maps": ["MagannathaAIE", "UltraloveAIE", "IncorporealAIE", "LeyLinesAIE", "PersephoneAIE", "PylonAIE", "TorchesAIE", "LastFantasyAIE"] }
  ]
}
```

---

## Performance

160×208 = 33,280 terrain tiles (vs 4,096 in emulated mode). Three.js individual `Mesh` objects per tile may be slow. Mitigation: only create geometry for non-LOW tiles (walls, high ground, ramps); LOW ground uses a single base plane. Reduces rendered objects to ~14,000 HIGH + ~700 RAMP + ~7,700 WALL = ~22,400 — better, but still needs profiling. If too slow: switch to `InstancedMesh` per tile type (follow-up optimisation, not blocking this feature).

---

## Files

**New:**
- `src/main/java/io/quarkmind/sc2/map/SC2MapTerrainExtractor.java`
- `src/main/java/io/quarkmind/sc2/map/MapDownloader.java`
- `src/main/java/io/quarkmind/sc2/map/SC2MapCache.java`
- `src/main/java/io/quarkmind/qa/TerrainResource.java`
- `src/main/java/io/quarkmind/qa/CurrentMapResource.java`
- `src/main/resources/map-registry.json`

**Modified:**
- `src/main/java/io/quarkmind/sc2/SC2Engine.java` — add `getMapName/Width/Height()` defaults
- `src/main/java/io/quarkmind/sc2/replay/ReplayEngine.java` — parse metadata, implement map getters
- `src/main/java/io/quarkmind/qa/EmulatedTerrainResource.java` — extract shared `TerrainResponse` record
- `src/main/resources/META-INF/resources/visualizer.js` — two-phase terrain loading

**Not changed:** `TerrainGrid.java`, `GameStateBroadcast.java`, `EmulatedTerrainResource` logic

---

## Testing

1. **Unit:** `SC2MapTerrainExtractorTest` — extract from `TorchesAIE_v4.SC2Map` (pre-downloaded at `/tmp/sc2maps/`), assert grid dimensions 160×208 and known tile types (border at x=0 → WALL, center → LOW/HIGH)
2. **Unit:** `MapDownloaderTest` — mock HTTP, verify zip extraction and name fuzzy match
3. **Manual:** `mvn quarkus:dev -Dquarkus.profile=replay` → open visualizer → `curl localhost:8080/qa/current-map` → terrain loads in browser

---

## Open Issues (Follow-up)

| Issue | Description |
|---|---|
| Terrain tile performance | Measure FPS with 33K tiles; add InstancedMesh if needed |
| Magannatha/Ultralove version match | Verify terrain matches for `_v2` vs unversioned file |
| Replay controls | Play/pause/scrub — separate epic (E23) |
