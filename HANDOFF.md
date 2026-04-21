# Handover — 2026-04-21

**Head commit:** `022242e` — docs: E14 blog entry, CLAUDE.md window.__test API, spec update + screenshots

## What Changed This Session

**E14 complete — 3D Visualizer + Protoss Cartoon Sprites (closes #84, refs epic #83)**

- PixiJS replaced with Three.js client-side only — server WebSocket protocol unchanged
- 3D terrain: BoxGeometry tiles (walls raised, ramps, grid lines), shadow mapping, orbit camera (drag/scroll/pan, angle presets)
- Fog of war: per-tile PlaneGeometry overlays, `renderOrder=5`, `depthWrite:false`, driven by `visibility` string from `GameStateBroadcast`
- 4-direction directional cartoon sprites: Probe, Zealot, Stalker, enemy — Canvas 2D draw functions, `getDir4()` with negated dx for Three.js handedness
- `SpriteMaterial` must use `depthWrite:true` + `alphaTest:0.1` — default `depthWrite:false` causes fog planes to render through sprites at low angles
- Buildings/geysers as BoxGeometry (always-visible anchors, not in group2d/group3d)
- 3D sphere+eyes model toggle (`group2d`/`group3d`)
- Config panel gated on `EmulatedConfig.active` (`%emulated.emulated.active=true`) — HTTP status alone insufficient (returns 200 in `%test` too)
- 17 Playwright tests (6 disabled as PixiJS-specific), 475 non-Playwright tests, all green
- `window.__test` API: `threeReady()`, `terrainReady()`, `wsConnected()`, `hudText()`, `unitCount()`, `enemyCount()`, `buildingCount()`, `stagingCount()`, `geyserCount()`, `fogOpacity(x,z)`, `worldToScreen(wx,wz)`

**Issue cleanup:** Closed 12 previously-implemented issues (#49, #50, #51, #54, #58, #59, #60, #66, #75, #76, #77, #81)

**Garden:** 8 entries submitted — PR #90 on Hortora/garden (Three.js depthWrite gotcha, Object.assign position, Quarkus %test profile, getDir4 handedness, transient Map leak, directional sprite technique, terrainReady flag, brainstorm /files/ undocumented)

**Blog:** `docs/_posts/2026-04-21-mdp01-e14-a-new-visualizer.md`

## Immediate Next Step

No active epic. Open issues:

```bash
gh issue list --state open
```

Options: E15 (Terran cartoon art, depends on E14 ✓), E16 (Zerg), E17 (combat indicators), E18 (replay controls), or #74 (YAML genericisation — platform direction).

**Recommended:** E15 Terran cartoon art — follows directly from E14 foundation, no new architecture needed.

## Key Technical Notes

*E13 and earlier notes unchanged — retrieve with:* `git show HEAD~1:HANDOFF.md`

**E14 additions:**
- **`getDir4` negated dx** — `Math.atan2(-(camPos.x - unitPos.x), camPos.z - unitPos.z)` — positive dx inverts left/right without the negation
- **`SpriteMaterial` depthWrite** — must set `depthWrite:true, alphaTest:0.1` explicitly; default is false unlike all other Three.js materials
- **Fog stride** — `visibility.charAt(gz * GRID_W + gx)` not `gz * 64` — hardcoded 64 works today but breaks if grid width changes
- **`stagingMeshes`** — module-level Map for staging unit 3D models; passing `new Map()` per call was a silent leak
- **`EmulatedConfig.active`** — profile-gate pattern: `@ConfigProperty(name="emulated.active", defaultValue="false")` with `%emulated.emulated.active=true` in properties

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer + Cartoon Sprites | Open — sub-epics E15/E16/E17/E18 remain |
| #74 | Unit genericisation / configurable YAML | Parked — platform direction |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E14 design spec | `docs/superpowers/specs/2026-04-21-e14-3d-visualizer-protoss-sprites-design.md` |
| E14 implementation plan | `docs/superpowers/plans/2026-04-21-e14-3d-visualizer.md` |
| Blog entry | `docs/_posts/2026-04-21-mdp01-e14-a-new-visualizer.md` |
| E13 handover (prior) | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (#84 closed; epic #83 open) |
