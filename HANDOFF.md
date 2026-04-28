# Handover — 2026-04-28 (end of session)

**Head commit:** `395f7d5` — camera auto-centre + mineral depletion tests

## What Changed This Session

**Visual testing overhaul** — the critical lesson: `@QuarkusTest` Playwright tests always run mock profile. They proved nothing about the replay viewer. Built `ReplayVisualizerIT` which starts the real replay jar as a subprocess on port 8082, opens headless Chromium, samples actual WebGL pixels. Three bugs surfaced:
- `preserveDrawingBuffer: false` (Three.js default) makes pixel sampling silently return black
- Creep at `y=0.02` was below the ground plane at `y=0.04` — depth-tested invisible
- `BoxGeometry` minerals occluded by cliff geometry from isometric angle → switched to `THREE.Sprite`

Fixed: `depthTest:false` + `renderOrder:10` on creep, sprites for minerals/geysers, brighter colours.

**Run real visual tests:** `mvn test -Pplaywright-replay` (builds jar, samples pixels, asserts cyan/green/purple)

**Building click-to-inspect (#112)** — `GET /qa/building/{tag}`, raycaster extended to hit `buildingMeshes` + `enemyBuildingMeshes`, `clickBuilding(tag,isEnemy)` in `window.__test`.

**Camera auto-centre (#113)** — `autocentreCamera(state)` fires once on first frame with `myBuildings`, sets `camTarget` to Nexus world position. Replay now opens at the Protoss base.

**Mineral depletion (#114)** — `removeMineralPatchByTag` on `UnitDied` was already working. Tests document and lock in: count < 154 at game-end, monotonically non-increasing.

## Immediate Next Step

No open issues. All immediate work complete. Good candidates for a new epic:
- **Enemy active AI (E4)** — emulated game has scripted waves; real enemy economy + production would make it a training ground
- **Pathfinding + terrain (E5)** — terrain data is live; A* is the next emulator layer  
- **Scouting CEP calibration** — 22 AI Arena replays exist to calibrate ROACH_RUSH/4GATE thresholds

Before starting any of these: brainstorm first, create GitHub epic + child issues.

## Key Technical Notes

- **`mvn test -Pplaywright-replay`** — the only test command that proves elements are visible to a human. Requires replay jar built first (profile does it automatically).
- **`ReplayVisualizerIT`** — starts jar on port 8082, `@BeforeAll`/`@AfterAll` lifecycle. Add `focusOnFirstX()` + pixel test for any new visual element.
- **`preserveDrawingBuffer:true`** on `WebGLRenderer` is required for `samplePixel()` to work.
- **`autocentreCamera`** fires once, guarded by `cameraCentred` flag, resets on `ws.onopen`.
- *Unchanged notes — `git show HEAD~1:HANDOFF.md`*

## Open Issues

| # | What | Status |
|---|------|--------|
| #74 | Unit genericisation as YAML | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-04-28-mdp01-seeing-is-believing.md` |
| Pixel test infrastructure | `src/test/java/io/quarkmind/qa/ReplayVisualizerIT.java` |
| Replay profile | `mvn test -Pplaywright-replay` (pom.xml) |
| Prior handover | `git show HEAD~1:HANDOFF.md` |
