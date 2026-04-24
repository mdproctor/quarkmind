# Handover — 2026-04-24 (end of session)

**Head commit:** `141f09f` — full 3-race building coverage

## What Changed This Session

**E20: Protoss building sprites** — replaced BoxGeometry building meshes with canvas sprite planes. `BUILDING_MATS` map, `makeBuildingTexture()`, `BUILDING_SCALE` replaces old `BUILDING_H/W/COLOUR`. 9 draw functions (Nexus → Twilight Council). `spawnBuildingForTesting(BuildingType, Point2d)` added to `SimulatedGame`.

**E21: Full 3-race building coverage** — `BuildingType` expanded from 9 → 48 types:
- Protoss: +6 (PhotonCannon, ShieldBattery, DarkShrine, TemplarArchives, FleetBeacon, RoboticsBay)
- Terran: +15 (CommandCenter → Refinery)
- Zerg: +18 (Hatchery → Extractor)

Files changed: `BuildingType.java`, `SC2Data.java`, `Sc2ReplayShared.java` (includes flying/state variants), `ObservationTranslator.java` (`ALL_BUILDINGS` set), `ActionTranslator.java` (3 Zerg/Protoss abilities map to `null` — no ocraft constant exists). 39 new canvas draw functions. Showcase seeds 49 buildings (z=22..34, 6 rows).

**Playwright:** 169 → 218 tests, all passing. Showcase asserts `buildingCount()==49`.

**Showcase layout:** Building rows at tile z=22,24,26,28,32,34. **Negative tile z is out of bounds** — tile z=-2 maps to worldZ=-23.8, exceeding ±23. Use z≥0 only.

## Immediate Next Step

**Epic #83 still open.** Showcase now covers 65 units + 49 buildings — dev server running at localhost:8080, seeded with `curl -X POST http://localhost:8080/sc2/showcase`. Natural next: enemy buildings (GameState only tracks `myBuildings`; `enemyUnits` covers enemy structures as units — no `enemyBuildings` field exists). Or pivot to a different epic entirely — worth checking #83 scope.

## Key Technical Notes

- **ocraft Abilities gaps:** `BUILD_TEMPLAR_ARCHIVES`, `RESEARCH_LURKER_LEVEL`, `BUILD_NYDUS_CANAL` don't exist in ocraft 0.4.21 — map to `null` in `ActionTranslator.mapBuildAbility()`.
- **Showcase tile-z constraint:** `gw(gx,gz) = { x: gx*TILE - HALF_W, z: gz*TILE - HALF_H }`. For 64×64 grid, HALF_H=22.4. Tile z=0 → worldZ=-22.4. Negative tile z → outside ±23 bounds.
- **`smokeTestDrawFn` lookup:** All 48 building draw functions registered (buildings don't need directional variants — smoke test uses dir=0 only).
- *Unchanged technical notes (ClassTooLargeException, FLYING_UNITS, fog trap) — `git show HEAD~1:HANDOFF.md`*

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer | Open — units + buildings complete |
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-04-24-mdp01-buildings-all-three-races.md` |
| Prior handover | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (#93 closed, #94 closed, #83 open) |
