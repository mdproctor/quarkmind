# Handover — 2026-04-22

**Head commit:** `58e3009` — docs: add blog entry E15 — Terran sprites and the cost of looking instead of testing

## What Changed This Session

**E15 complete — Terran sprites + team colour decal system (closes #85, refs epic #83)**

- Marine, Marauder, Medivac draw functions — all 4 directions × 2 team colours
- `makeDirTextures(drawFn, teamColor, size)` — teamColor drives SC2-style decal zones (visor, shoulder pads, knee plates, engine glow)
- `UNIT_MATS['TYPE_F']`/`['TYPE_E']` dispatch — unknown types fall back to `UNKNOWN_F`/`UNKNOWN_E`
- `FLYING_UNITS = new Set(['MEDIVAC'])` — drives Y-offset (sprite at `TILE*1.5`, 3D model at `TERRAIN_SURFACE_Y` + offset)
- `TERRAIN_SURFACE_Y` JS variable — 0.08 mock / `TILE` emulated — fixes 3D model ground height per profile
- Fog planes only created in emulated mode (`hasRealTerrain` guard) — removes 4096 objects from scene graph in mock, restoring 60 FPS
- Camera default target moved to `(-16, 0, -16)` — near tile (9,9) where player base lives
- `ShowcaseResource` — seeds all 7 sprite types for visual demo; uses `simulatedGame.reset()` not `orchestrator.startGame()` to keep AI idle

**Bug fixes (found during showcase debugging):**
- `BasicEconomicsTask.pylonPosition()` — unbounded row index produced tile y=1752 at buildingCount=2317; fixed with `% 16` wrap
- `EconomicsDecisionService.checkSupply()` — no duplicate-Pylon guard; added in-progress check

**New tests:**
- `GameStateInvariantTest` — plain JUnit; positions on-map, tags unique, building count plausible, health ≤ max after N ticks
- `BasicEconomicsTaskTest` additions — parameterized `pylonPositionIsAlwaysWithinMapBounds`, `doesNotQueueSecondPylonWhileOneIsUnderConstruction`
- `VisualizerRenderTest.allSceneObjectsAreWithinMapBounds` — Playwright; traverses full Three.js scene, fails if any mesh outside ±23 world units

**Garden:** 4 entries submitted — PR #96 (Hortora/garden): leaveGame WebSocket gotcha, Quarkus hot-reload silent failure, Three.js visible=false scene-graph trap, scene.traverse Playwright technique

**Blog:** `docs/_posts/2026-04-22-mdp01-e15-terran-sprites-and-testing.md`

## Immediate Next Step

No active epic. Open issues:
```bash
gh issue list --state open
```

Options: E16 (Zerg cartoon art), E17 (combat indicators), E18 (replay controls), or #74 (YAML genericisation).

**Recommended:** E16 Zerg sprites — follows directly from E15 foundation. Same architecture, new race.

## Key Technical Notes

*E14 and earlier notes unchanged — retrieve with:* `git show HEAD~1:HANDOFF.md`

**E15 additions:**
- `ShowcaseResource` at `/sc2/showcase` (POST) — seeds showcase; call AFTER browser connects
- Dev server showcase workflow: `mvn quarkus:dev` → open browser → `curl -X POST http://localhost:8080/sc2/showcase`
- `TERRAIN_SURFACE_Y` — module-level JS var set in `loadTerrain()`; use this (not hardcoded `TILE`) for 3D model Y
- 4096 fog planes only exist in emulated mode — `fogPlanes.size === 0` in mock is correct behaviour

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer (E16/E17/E18 remain) | Open |
| #74 | Unit genericisation / configurable YAML | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E15 design spec | `docs/superpowers/specs/2026-04-21-e15-terran-sprites-design.md` |
| E15 implementation plan | `docs/superpowers/plans/2026-04-21-e15-terran-sprites.md` |
| Blog entry | `docs/_posts/2026-04-22-mdp01-e15-terran-sprites-and-testing.md` |
| E14 handover (prior) | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (#85 closed; epic #83 open) |
