# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-15 — E8: Terrain height — high/low ground mechanics

**Priority:** high
**Status:** active

Extend E7's binary `WalkabilityGrid` to a three-value `TerrainGrid` (high/low/wall).
Ramps only walkable from specific directions. High ground gives vision advantage;
ranged attacks from low against high ground have 25% miss chance (real SC2 mechanic).
Visualizer shades tiles by height level.

**Context:** Came up during E7 brainstorm. Deliberately deferred to keep E7 focused on
walkability + A* pathfinding. The `WalkabilityGrid` API is designed so `TerrainGrid`
can extend or replace it without touching `AStarPathfinder`.

**Promoted to:**
