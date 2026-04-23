# Handover — 2026-04-23

**Head commit:** `b285200` — docs: add blog entry E16 — Zerg sprites and the showcase that lied

## What Changed This Session

**E16 complete — Zerg sprites (closes #86, refs epic #83)**

- `drawZergling`, `drawRoach`, `drawHydralisk`, `drawMutalisk` — canvas 2D draw functions, all 4 dirs × 2 team colours, bio-sac teamColor decal (radial gradient + shadowBlur)
- `FLYING_UNITS` now includes `'MUTALISK'`
- 22 `UNIT_MATS` keys registered (was 14 after E15)
- 12 new Playwright tests — smoke, spawn, elevation (mutaliskSpawnsHigherThanGroundUnit)
- `ShowcaseResource` updated — Zerg row at tiles (10,11)–(14,13) area, all within Nexus sight range

**Showcase fixes (two recurring bugs killed)**

- Root cause of fog/wrong-units bug: `ShowcaseResource` seeds `SimulatedGame` but in emulated mode `EmulatedEngine` broadcasts `EmulatedGame` — different CDI bean, no error. Showcase must always run in mock mode (`mvn quarkus:dev`, no profile flag). Documented in CLAUDE.md.
- Sprite/3D model Y fixed: was `TILE * 0.65 = 0.455` (below terrain surface in emulated mode where `TERRAIN_SURFACE_Y = TILE = 0.7`). Now `TERRAIN_SURFACE_Y + TILE * 0.5` (ground) / `TERRAIN_SURFACE_Y + TILE * 1.1` (flying).
- New Playwright test: `showcaseRendersAllUnitsAboveTerrainSurface` — must pass before showing any showcase. Asserts 10 enemies render, all Y > `TERRAIN_SURFACE_Y`, no bounds overflows.

**Terrain colour scheme changed**

- Sun: `0xaabbff` (blue-white) → `0xffffff` (neutral white) — blue-white sun killed warm tile colours
- Ground tiles: `0x1a2233` → `0xb8956a` (sandy light brown)
- Fog planes (emulated): `0x000000` → `0x888888` (light grey for out-of-vision)

**3D unit models — idea logged in IDEAS.md** (after 2D sprites complete, low-poly Three.js compound geometry, perf gate)

## Immediate Next Step

No active epic. Recommended: E17 — continue 2D sprites (11 Protoss remaining, 10 Terran, 9 Zerg; all on UNKNOWN fallback).

```bash
gh issue list --state open
```

## Key Technical Notes

*E15 and earlier notes unchanged — retrieve with:* `git show HEAD~1:HANDOFF.md`

**E16 additions:**
- Showcase must run mock mode — `mvn quarkus:dev` (no profile). Seeding in emulated mode silently shows EmulatedGame state, not SimulatedGame.
- `TERRAIN_SURFACE_Y` is the profile-aware vertical anchor — all unit Y must be relative to it, never absolute `TILE * n`
- `smokeTestDrawFn` lookup uses `typeof` hoisting — draw functions must be `function` declarations, not arrow fns
- `showcaseRendersAllUnitsAboveTerrainSurface` Playwright test is mandatory before showing any showcase result

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer (E17/E18/… remain) | Open |
| #74 | Unit genericisation / configurable YAML | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E16 design spec | `docs/superpowers/specs/2026-04-22-e16-zerg-sprites-design.md` |
| E16 implementation plan | `docs/superpowers/plans/2026-04-22-e16-zerg-sprites.md` |
| Blog entry | `docs/_posts/2026-04-23-mdp01-e16-zerg-sprites-showcase-lies.md` |
| 3D models idea | `IDEAS.md` (entry 2026-04-23) |
| E15 handover (prior) | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (#86 closed; epic #83 open) |
