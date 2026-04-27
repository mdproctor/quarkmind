# Handover — 2026-04-27 (end of session)

**Head commit:** `237649c` — test(visualizer): add E2E click-to-inspect unit panel tests

## What Changed This Session

**Map completeness epic #106 — all 5 issues closed:**

- **#107** — `ReplaySimulatedGame.applyUnitBorn()` now captures `ctrlId==0` units into `mineralPatches` (geysers already existed). Both cleared in `reset()` (was missing — caused test state bleed).
- **#108** — Enemy buildings tracked via `UnitBorn/UnitInit/UnitDone/UnitDied`. `GameState` gained `enemyBuildings: List<Building>`.
- **#109** — Three.js: `syncMineralPatches()`, `syncEnemyBuildings()`, `syncCreep()`. Showcase seeds 8 minerals + 1 Hatchery. `window.__test` gains `mineralCount()`, `enemyBuildingCount()`, `creepTileCount()`.
- **#110** — `#btn-enemy-toggle` shows/hides enemy layer. `window.__test.enemyLayerVisible()`.
- **#111** — Creep approximated from HATCHERY/LAIR/HIVE positions, `CREEP_RADIUS=10` tiles, purple overlay.

**Click-to-inspect E2E tests:**
- `window.__test.clickUnit(tag, isEnemy)` — async, fires real raycaster, awaits fetch+DOM pipeline
- `window.__test.unitScreenPos(tag)` — projects sprite's actual Three.js position through camera
- `worldToScreen(wx, wz, wy=0)` — wy parameter added (was hardcoded 0; caused ~27px miss)
- Portrait draw bug fixed: `drawX(ctx, 32, 32, 0, tColor)` → `drawX(ctx, 32, 0, tColor)` (silently swallowed by `.then()`)
- `showUnitPanelAsync()` + `_populateUnitPanel()` extracted; `clickUnit` is async and awaited

**Bug fixes:**
- `SimulatedGame.reset()` was missing `enemyBuildings.clear()` and `mineralPatches.clear()` — caused `@QuarkusTest` state bleed between tests.

## Immediate Next Step

No open epic. All #106 child issues closed. Good candidates for a new epic:
- **Live SC2 smoke test (#13)** — blocked on SC2 availability
- **Replay viewer: enemy unit click-to-inspect** — `UnitResource` only searches `myUnits` and `enemyUnits`, not buildings. Enemy buildings have no inspect endpoint yet.
- **Replay viewer: building inspect panel** — `UnitResource` doesn't handle buildings; would need `BuildingResource` and raycaster extended to include `buildingMeshes` + `enemyBuildingMeshes`.

## Key Technical Notes

- **`async window.__test` helpers + `page.evaluate("async () =>")`** — the reliable pattern for testing async UI pipelines in Playwright. `clickUnit` awaits the full fetch+DOM pipeline before resolving; `page.evaluate` awaits the Promise. No `waitForFunction` polling needed.
- **`SimulatedGame.reset()` vs `clearAll()`** — two separate methods. New collections must be added to BOTH. `@BeforeEach` calls `reset()` not `clearAll()`.
- **`worldToScreen` y=0 miss** — sprites at `TERRAIN_SURFACE_Y + TILE*0.5 ≈ 0.43` project ~27px off from y=0 in isometric view. Always pass `wy` for sprite-level click targets.
- **camTarget = (-16, 0, -16)** — camera looks at tile ≈ (9,9), the probe spawn area in mock mode. Not the map centre.
- *Unchanged notes — `git show HEAD~1:HANDOFF.md`*

## Open Issues

| # | What | Status |
|---|------|--------|
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-04-27-mdp01-map-fills-in.md` |
| Epic #106 (closed) | mdproctor/quarkmind — all 5 child issues closed |
| Prior handover | `git show HEAD~1:HANDOFF.md` |
