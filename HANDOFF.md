# Handover — 2026-04-23 (end of session)

**Head commit:** `ee7a2b0` — docs: E19a/b/c plans

## What Changed This Session

### Siege Tank Sieged sprite (#83)
- `drawSiegeTankSieged` — distinct deployed artillery silhouette (wide splayed tracks, steep-angle barrel, stabiliser struts)
- `UnitType.SIEGE_TANK_SIEGED` added to domain model
- `SiegeTankSieged` mapped in `Sc2ReplayShared` (was previously collapsed to `SIEGE_TANK`)

### E18 Sprite coverage complete (at session start)
- E18a (11 Protoss) + E18b (9 Zerg) were already done
- Showcase rewritten: 40 units in 6×7 grid within Nexus sight range (all visible in browser)
- Showcase validation: 40 enemies, all above terrain surface, all within map bounds

### E19: 24 new multiplayer unit types — full coverage
Added all previously-missing multiplayer units to `UnitType` enum, `Sc2ReplayShared` mapping, and `ActionTranslator`. Then implemented sprites for all 24:

**E19a — Terran (issue #90, closed):**
SCV, Reaper, Hellion, Hellbat, MULE, Viking Assault, Liberator AG

**E19b — Protoss (issue #91, closed):**
Phoenix, Oracle, Tempest, Mothership, Warp Prism, Warp Prism Phasing, Interceptor, Adept Phase Shift

**E19c — Zerg + spawned (issue #92, closed):**
Drone, Overlord, Overseer, Baneling, Locust, Broodling, Infested Terran, Changeling, Auto Turret

**Plus Liberator AG added to FLYING_UNITS; Overlord, Overseer, Locust added to FLYING_UNITS.**

**Total test count after session: 169 Playwright tests, 0 failures. 506 unit tests, 0 failures.**

## Immediate Next Steps

1. **Showcase needs updating** — still shows the original 40 units (pre-E19). The 24+ new units are not in the showcase yet. Update `ShowcaseResource.java` to include them — will need a grid rethink since 64 units won't fit in the current 6×7 sight-range grid. Options: use multiple Nexus sight ranges by seeding friendly observers at map corners, or accept a scrollable showcase outside the fog constraint.

2. **Epic #83 still open** — remaining deferred scope: building sprites, walk-cycle animations, combat indicators, replay controls. Sprite work is now functionally complete (all multiplayer units covered).

3. **Viking Assault is ground** — `VIKING_ASSAULT` is NOT in `FLYING_UNITS`. Viking Fighter (air) is `VIKING`, Viking ground form is `VIKING_ASSAULT`. Both have sprites but only `VIKING` is in the air set.

## Key Technical Notes

- `smokeTestDrawFn` lookup table: now at lines ~98–200+ in `visualizer.js` (grown significantly). Every draw function needs an entry there.
- `visualizer.js` is now ~6000+ lines with 70+ UNIT_MATS entries.
- `FLYING_UNITS` set: 16 entries — MEDIVAC, MUTALISK, VIKING, RAVEN, BANSHEE, LIBERATOR, BATTLECRUISER, OBSERVER, VOID_RAY, CARRIER, BROOD_LORD, CORRUPTOR, VIPER, LIBERATOR_AG, OVERLORD, OVERSEER, LOCUST, PHOENIX, ORACLE, TEMPEST, MOTHERSHIP, WARP_PRISM, WARP_PRISM_PHASING, INTERCEPTOR.
- Showcase runs in mock mode only (`mvn quarkus:dev` no flags). Seed with `curl -X POST http://localhost:8080/sc2/showcase`.

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer | Open — sprite work done, deferred scope remains |
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E19a plan | `docs/superpowers/plans/2026-04-23-e19a-terran-new-sprites.md` |
| E19b plan | `docs/superpowers/plans/2026-04-23-e19b-protoss-new-sprites.md` |
| E19c plan | `docs/superpowers/plans/2026-04-23-e19c-zerg-spawned-sprites.md` |
| E18 handover (prior) | `git show HEAD~50:HANDOFF.md` (approx) |
| GitHub | mdproctor/quarkmind (epic #83 open; #88–92 closed) |
