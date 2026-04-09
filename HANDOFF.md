# Handover — 2026-04-09

**Head commit:** `de1daf7` — docs: design snapshot 2026-04-09 — emulation E1 + visualizer complete

## What Changed This Session

- **Phase E1 emulation engine** — `EmulatedGame` + `EmulatedEngine` + `%emulated` profile. Probe-driven mineral harvesting. `SC2Data` extracted as shared constants. `applyIntent()` stub (E2 will wire it).
- **QuarkMind Visualizer** — PixiJS 8 (bundled, no CDN) + WebSocket push (`GameStateBroadcaster` as frame listener) + Electron wrapper. Sprite proxy (`SpriteProxyResource`) solves WebGL CORS. `window.__test` API for testing.
- **Integration tests** — `GameStateWebSocketTest` (5, uses `java.net.http.WebSocket`), `VisualizerRenderTest` (9 Playwright tests: sprite counts, positions, mask regression, pixel sampling). 210 tests total.
- **Performance benchmark** — `GameLoopBenchmarkTest` (`@Tag("benchmark")`, `mvn test -Pbenchmark`). Per-phase `TickTimings` exposed via `AtomicReference` in `AgentOrchestrator`. Pre-E2 baseline: 2ms mean plugin time.
- **E2 spec written** — movement, scripted enemy wave, full intent handling, `EmulatedConfig` (properties + live overrides), visualizer config panel.
- **Garden** — GE-0144–0151 submitted (PixiJS 8 mask bug, Playwright Java arg order, Tyrus classloader, Java 11 WS `request()`, JAX-RS `@ApplicationScoped`, Surefire `combine.self`, `window.__test`, WS connectivity proof).

## Immediate Next Step

**Implement E2.** Spec at `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md`. Write the plan (writing-plans skill), then execute subagent-driven. Key components:
1. `SC2Data` — add `mineralCost()`, `gasCost()`
2. `EnemyWave` record
3. `EmulatedConfig` CDI bean (`@ConfigProperty` defaults + volatile runtime fields)
4. `EmulatedConfigResource` (`GET/PUT /qa/emulated/config`)
5. `EmulatedGame` — movement, pendingCompletions, enemy waves, full `applyIntent()`
6. `EmulatedEngine` — inject config, sync speed per tick
7. `visualizer.html/js` — config panel sidebar (hidden in non-emulated profiles)

After E2: run benchmark, record result in `docs/benchmarks/`.

## Open Issues

| # | What | Blocker |
|---|---|---|
| #13 | Live SC2 smoke test | SC2 binary needed |
| #14 | GraalVM native image tracing | Blocked on #13 |
| #15 | FlowEconomicsTask budget arbitration | None — worth fixing after E2 |
| #16 | Scouting CEP threshold calibration | Replay data |

## Key Technical Notes

- **PixiJS 8 mask bug** — `sprite.addChild(mask); sprite.mask = mask` makes anchored sprites invisible. Fix: no mask. Documented in garden GE-0144.
- **WebSocket test isolation** — call `engine.observe()` directly (not `orchestrator.gameTick()`) to avoid triggering async Flow economics which pollutes `IntentQueue` across tests.
- **Playwright tests** — Chromium must be installed: `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"`. Tests use `window.__test` API, not pixel comparison.
- **Benchmark** — `mvn test -Pbenchmark`. Uses `combine.self="override"` in Maven profile to clear base `<excludedGroups>benchmark</excludedGroups>`.

## References

| Context | Where |
|---|---|
| Design snapshot | `docs/design-snapshots/2026-04-09-emulation-e1-visualizer-complete.md` |
| E2 spec | `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md` |
| Visualizer spec | `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md` |
| Benchmark baseline | `docs/benchmarks/2026-04-09-pre-e2-baseline.md` |
| GitHub | mdproctor/quarkmind |
