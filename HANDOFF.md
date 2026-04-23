# Handover — 2026-04-23 (end of session)

**Head commit:** `1bdaf5c` — showcase all 65 sprites

## What Changed This Session

**Sprite coverage complete — all 65 multiplayer units now have draw functions.**

E18a (11 Protoss) + E18b (9 Zerg) closed the original domain model. Then the replay parser gap was found: 24 units were falling through to UNKNOWN in `Sc2ReplayShared`. E19a (7 Terran), E19b (8 Protoss), E19c (9 Zerg/spawned) filled every gap.

New unit types added to enum + replay parser + ActionTranslator:
- **Terran:** SCV, REAPER, HELLION, HELLBAT, MULE, VIKING_ASSAULT, LIBERATOR_AG, AUTO_TURRET, SIEGE_TANK_SIEGED
- **Protoss:** PHOENIX, ORACLE, TEMPEST, MOTHERSHIP, WARP_PRISM, WARP_PRISM_PHASING, INTERCEPTOR, ADEPT_PHASE_SHIFT
- **Zerg:** DRONE, OVERLORD, OVERSEER, BANELING, LOCUST, BROODLING, INFESTED_TERRAN, CHANGELING

**Showcase extended to 65 units** in a 7×10 grid. Added `spawnFriendlyUnitForTesting` to `SimulatedGame` (was EmulatedGame only); 4 Probe observers at (4,5),(11,5),(4,15),(11,15) provide fog coverage. Playwright test asserts 65 enemies.

**CLAUDE.md updated:** `spawnFriendlyUnitForTesting` in SimulatedGame helpers section, showcase count 10→65, `mvn clean` note for ClassTooLargeException.

**Test count:** 169 Playwright tests, 0 failures.

## Immediate Next Step

**Building sprites** — natural next work. Same canvas 2D pattern as unit sprites. Start with `docs/superpowers/specs/` or brainstorm first if scope is unclear. Epic #83 still open.

Key prior art: `ShowcaseResource.java` seeds buildings via `simulatedGame` — check how buildings are currently spawned (BuildingType enum, `spawnBuilding` or equivalent) before writing the plan.

## Key Technical Notes

- **`ClassTooLargeException` in dev mode** — `mvn clean` before `mvn quarkus:dev` after large schema additions. Quarkus-generated startup handler hits JVM 64KB constant pool limit.
- **`smokeTestDrawFn` lookup table** — manual entries only; every draw function needs `if (typeof drawX !== 'undefined') lookup.drawX = drawX;` in the `smokeTestDrawFn` function body (~line 98–200 in visualizer.js).
- **FLYING_UNITS** now has 24 entries — MEDIVAC, MUTALISK, VIKING, RAVEN, BANSHEE, LIBERATOR, BATTLECRUISER, OBSERVER, VOID_RAY, CARRIER, BROOD_LORD, CORRUPTOR, VIPER, LIBERATOR_AG, OVERLORD, OVERSEER, LOCUST, PHOENIX, ORACLE, TEMPEST, MOTHERSHIP, WARP_PRISM, WARP_PRISM_PHASING, INTERCEPTOR.
- **Playwright fog trap** — `enemyCount()` counts scene objects, not visually visible units. Units in fog count but don't render. Always validate visually after showcase changes.
- **visualizer.js** is ~6000+ lines, 133 UNIT_MATS entries.

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer | Open — sprite work done, buildings next |
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-04-23-mdp02-e18-e19-the-full-roster.md` |
| E19 plans | `docs/superpowers/plans/2026-04-23-e19{a,b,c}-*.md` |
| Prior handover | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (epic #83 open; #88–92 closed) |
