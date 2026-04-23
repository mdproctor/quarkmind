# Handover — 2026-04-23

**Head commit:** `62c8d50` — docs: E18 design spec — remaining Protoss + Zerg sprites

## What Changed This Session

**E17 complete — all 10 Terran sprites (closes #87, refs epic #83)**

- 5 ground: Ghost, Cyclone, Widow Mine, Siege Tank (mobile), Thor
- 5 air: Viking, Raven, Banshee, Liberator, Battlecruiser
- FLYING_UNITS now: `['MEDIVAC','MUTALISK','VIKING','RAVEN','BANSHEE','LIBERATOR','BATTLECRUISER']`
- 25 new Playwright tests (smoke + spawn for all 10; elevation for 5 air)
- ShowcaseResource: 10 → 20 enemies (Terran ground column x=16 + air row y=8)
- Showcase Playwright test updated: expects 20 enemies
- Issue #87 closed

**Key discovery: `smokeTestDrawFn` has a manual lookup table** (lines ~98–130 in `visualizer.js`). Every new draw function must be added there with `if (typeof drawX !== 'undefined') lookup.drawX = drawX;` — not documented elsewhere.

**E18 spec written and committed**

- `docs/superpowers/specs/2026-04-23-e18-protoss-zerg-sprites-design.md`
- 11 Protoss + 9 Zerg = 20 units total
- Protoss air (FLYING_UNITS): OBSERVER, VOID_RAY, CARRIER
- Zerg air (FLYING_UNITS): BROOD_LORD, CORRUPTOR, VIPER

**E18 plan NOT written** — writing-plans hit the 32k token limit (20 units × full draw code in each step = too large).

## Immediate Next Step

**Write E18a plan (Protoss only) and E18b (Zerg), then execute via subagent-driven-development.**

**Critical:** Do NOT embed full draw function code in plan steps. Plan steps should describe what to implement visually; the implementation subagent writes the actual canvas code. This keeps each plan under the token limit.

Pattern to follow: `docs/superpowers/plans/2026-04-23-e17-terran-sprites.md` but with visual descriptions instead of pre-written draw code.

## Key Technical Notes

*E16 notes unchanged — retrieve with:* `git show HEAD~2:HANDOFF.md`

**E17/E18 additions:**
- `smokeTestDrawFn` lookup table at lines ~98–130 — manual, must be updated per unit. Every draw function must be a `function` declaration (not arrow fn) and added to this table.
- `visualizer.js` is now 2028 lines, 43 UNIT_MATS entries.
- No showcase extension for E18 — positions within 8.5 tiles of Nexus at (8,8) are exhausted. Showcase stays at 20 enemies.

**E18 visual design decisions (from spec):**
- ARCHON: pure energy rings + glowing core — no solid body
- LURKER: burrowed surface pose — spines erupting from ground mound
- DISRUPTOR: floating sphere with energy buildup glow
- COLOSSUS: very wide 4-legged walker, thermal lance arrays on top
- QUEEN: ground unit despite wings (not in FLYING_UNITS)
- VIPER: flying Zerg caster (in FLYING_UNITS)

## Open Issues

| # | What | Status |
|---|------|--------|
| #83 | Epic E14: 3D Visualizer — E18 is next | Open |
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E18 spec | `docs/superpowers/specs/2026-04-23-e18-protoss-zerg-sprites-design.md` |
| E17 plan (pattern) | `docs/superpowers/plans/2026-04-23-e17-terran-sprites.md` |
| E16 handover (prior) | `git show HEAD~2:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (epic #83 open; #87 closed) |
