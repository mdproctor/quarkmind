# QuarkMind — Design Snapshot
**Date:** 2026-04-09
**Topic:** Emulation Engine E1 + Live Visualizer complete
**Supersedes:** [2026-04-09-sc2-command-dispatch-complete](2026-04-09-sc2-command-dispatch-complete.md)
**Superseded by:** [2026-04-09-emulation-e3-combat-complete](2026-04-09-emulation-e3-combat-complete.md)

---

## Where We Are

QuarkMind runs a full agent loop against `EmulatedGame` — a physics simulation
engine that replaces the need for a live SC2 binary. Probe-driven mineral
harvesting is the first real mechanic. A PixiJS 8 visualizer, served by Quarkus
and updated via WebSocket each game tick, renders the live game state including
unit sprites from the Liquipedia SC2 image library. An Electron wrapper gives it
a native OS window. 210 tests pass — including 9 Playwright end-to-end render
tests that verify sprite counts, positions, and the PixiJS 8 mask regression.
A performance benchmark (pre-E2 baseline: 2ms mean plugin time) is in place for
tracking regressions. The E2 spec (movement + scripted enemy + intent handling +
live config UI) is written and ready for implementation.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Separate `EmulatedGame` from `SimulatedGame` | `EmulatedGame` in `sc2/emulated/` | `SimulatedGame` is the scripted test oracle; mixing physics would corrupt its determinism | Evolve SimulatedGame in-place |
| `SC2Data` in `domain/` | Shared constants for both engines | Eliminates drift between SimulatedGame and EmulatedGame data tables | Duplicate tables in each engine |
| `@IfBuildProfile("emulated")` on `EmulatedEngine` | Positive guard — active only in `%emulated` | Prevents CDI ambiguity without growing other engines' exclusion lists | Add "emulated" to all other @UnlessBuildProfile lists |
| WebSocket push instead of HTTP polling | `GameStateBroadcaster` as `SC2Engine` frame listener | Zero latency — pushed on each tick; no wasted polls when nothing changes | `setInterval(fetch)` polling |
| Sprite proxy (`SpriteProxyResource`) | Server-side Liquipedia fetch, re-served | WebGL texture loading enforces CORS; browser can't load cross-origin textures directly | Download sprites to git (binary bloat) |
| PixiJS 8 bundled locally | `pixi.min.js` in `META-INF/resources/` | No CDN dependency; works offline | CDN link in HTML |
| Electron spawns Quarkus as subprocess | `electron/main.js` with health poll | Single native window; Quarkus lifecycle managed by Electron | Separate terminal windows for Quarkus + browser |
| `window.__test` semantic API for canvas testing | Test hooks exposed from `visualizer.js` | PixiJS renders to WebGL canvas — no DOM selectors; semantic assertions survive visual changes | Screenshot pixel comparison (fragile) |
| `java.net.http.WebSocket` for WS tests | Built-in Java 11 client | Tyrus standalone client conflicts with Quarkus classloader; java.net.http has no dependencies | Tyrus, OkHttp |
| `@Tag("benchmark")` + Maven profile | `mvn test -Pbenchmark` | Benchmarks excluded from normal runs; profile uses `combine.self="override"` to clear base `<excludedGroups>` | JUnit @Disabled, separate Maven module |
| `AtomicReference<TickTimings>` in `AgentOrchestrator` | Non-invasive timing exposure | Benchmark reads last tick's phase breakdown without changing method signatures | Log parsing, JMH |

## Where We're Going

**Next steps:**
- Phase E2 implementation — movement (vector toward target), scripted enemy wave at frame 200, full intent handling (MoveIntent/AttackIntent/TrainIntent/BuildIntent), cost deduction, `EmulatedConfig` with live visualizer config panel
- Fix #15 — `FlowEconomicsTask` budget arbitration bug (overcommits per tick)
- Live SC2 smoke test (#13) — blocked on SC2 availability
- Run benchmark after E2 lands and record result in `docs/benchmarks/`

**Open questions:**
- Scouting CEP thresholds (#16) — R&D estimates, need calibration against replay data
- `FlowEconomicsTask` per-tick overhead under real SC2 (500ms/tick)
- Expansion detection heuristic accuracy against real SC2
- GOAP goal assignment hot-reload — enabled by DRL but never exercised
- Playwright Chromium install in CI — currently requires manual install step

## Linked ADRs

*(No ADRs created yet — strong candidates: SC2Data placement in domain/, EmulatedGame/SimulatedGame separation, WebSocket push vs polling, window.__test API for canvas testing.)*

## Context Links

- E1 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e1-design.md`
- E2 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md`
- Visualizer spec: `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md`
- Benchmark baseline: `docs/benchmarks/2026-04-09-pre-e2-baseline.md`
- GitHub issues: #13 (SC2 smoke test), #14 (GraalVM tracing), #15 (budget bug), #16 (CEP thresholds)
- Previous snapshot: `docs/design-snapshots/2026-04-09-sc2-command-dispatch-complete.md`
