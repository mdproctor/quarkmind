# QuarkMind — Design

## Overview

QuarkMind (formerly "starcraft", package root `io.quarkmind`) is a Quarkus application that plays StarCraft II (Protoss) as a plugin platform. Primary purpose is R&D: a living testbed for Drools, Quarkus Flow, and CaseHub (a Blackboard/CMMN framework). The platform provides scaffolding, SC2 connection, and the CaseHub control loop; intelligence is provided by swappable plugins behind CDI seams.

All four plugin seams (Strategy, Economics, Tactics, Scouting) are implemented using different R&D frameworks. The bot can connect to a live SC2 process and issue real game commands. An emulation engine (`EmulatedGame`) provides physics-based game simulation without requiring a live SC2 binary, served with a PixiJS 8 live visualizer in an Electron window.

**GitHub:** `mdproctor/quarkmind`
**Test count:** 236 (unit + integration + Playwright E2E)

---

## Architecture

```
SC2Client  →  GameObserver  →  GameStateTranslator  →  AgentOrchestrator
                                                               │
                                                     CaseHub CaseEngine
                                                    (Blackboard control loop)
                                                               │
                                    ┌──────────┬──────────────┼──────────────┐
                                StrategyTask EconomicsTask TacticsTask ScoutingTask
                                    └──────────┴──────────────┴──────────────┘
                                                  (plugin seams)
                                                               │
                                              CommandDispatcher / IntentQueue
                                                               │
                                                      ActionTranslator
                                                   (Intent → ResolvedCommand)
```

The game loop fires once per Quarkus Scheduler tick. Each tick:
1. `SC2Engine.tick()` advances the internal clock (mock/emulated only; no-op for real SC2)
2. `SC2Engine.observe()` returns the current `GameState`
3. `GameStateTranslator` converts it to a CaseHub CaseFile map
4. `AgentOrchestrator` cycles the CaseEngine; plugins fire and produce `Intent` objects
5. `SC2Engine.dispatch()` drains the `IntentQueue` → `ActionTranslator.translate()` → `ResolvedCommand` records applied via ocraft `ActionInterface`

In the `%emulated` profile, `EmulatedGame` replaces the SC2 client with a physics simulation engine; `GameStateBroadcaster` pushes state to a PixiJS 8 visualizer via WebSocket each tick.

---

## Domain Model

Plain Java records in `domain/` — no framework dependencies, always native-compatible.

| Record | Purpose |
|---|---|
| `GameState` | Snapshot: minerals, vespene, supply, unit lists, game frame |
| `Unit` | Single unit: tag, type, position, health, shields, maxShields |
| `Building` | Single building: tag, type, position, health, isComplete |
| `UnitType` | Enum: PROBE, ZEALOT, STALKER, IMMORTAL, COLOSSUS, CARRIER, etc. |
| `BuildingType` | Enum: NEXUS, PYLON, GATEWAY, CYBERNETICS_CORE, etc. |
| `Point2d` | Map coordinate |
| `PendingCompletion` | Under-construction building: completesAtTick, buildingType |
| `GameStateTick` | Immutable record passed to FlowEconomicsTask; Jackson-serialisable |
| `ResourceBudget` | Per-tick spending budget: minerals/vespene consumed by plugins |

`SC2Data` in `domain/` provides shared constants for both `SimulatedGame` and `EmulatedGame`: damage-per-tick, attack range, supply cost, shield values. Centralised here to eliminate drift between engines.

---

## Component Structure

| Package | Responsibility |
|---|---|
| `domain/` | Plain Java records — no CDI, no framework imports |
| `sc2/` | CDI interfaces: `SC2Engine` (unified engine seam), `ScenarioRunner`, `IntentQueue` |
| `sc2/intent/` | Sealed `Intent` interface + types: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent` |
| `sc2/mock/` | Mock implementation: `SimulatedGame`, `MockGameObserver`, `MockCommandDispatcher` |
| `sc2/mock/scenario/` | `ScenarioLibrary` — living specification of SC2 behaviour |
| `sc2/real/` | Real SC2: `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent`, `ObservationTranslator`, `ActionTranslator` |
| `sc2/emulated/` | Physics simulation: `EmulatedGame`, `EmulatedEngine`, `SC2Data` (shared with domain) |
| `sc2/replay/` | Replay-driven: `ReplayEngine` (observe-only), `ReplaySimulatedGame` |
| `agent/` | `AgentOrchestrator`, `GameStateTranslator`, `QuarkMindCaseFile` (key constants) |
| `agent/plugin/` | Plugin seam interfaces: `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| `plugin/` | Real plugin implementations: `BasicEconomicsTask`, `BasicScoutingTask`, `BasicTacticsTask`, `DroolsStrategyTask`, `FlowEconomicsTask`, `DroolsTacticsTask` |
| `plugin/drools/` | Drools Rule Units: `StrategyRuleUnit`, `TacticsRuleUnit`, `ScoutingRuleUnit`, `.drl` rule files |
| `plugin/flow/` | Quarkus Flow: `EconomicsFlow`, `FlowEconomicsTask` |
| `agent/QuarkMindTaskRegistrar` | Startup bean wiring all four plugin seams into `TaskDefinitionRegistry` |
| `visualizer/` | `GameStateBroadcaster` (WebSocket push), `SpriteProxyResource` (Liquipedia CORS proxy) |
| `qa/` | QA REST endpoints — dev/test only (`@UnlessBuildProfile("prod")`) |
| `electron/` | Electron wrapper — `main.js` spawns Quarkus as subprocess, health-polls, manages window |
| `META-INF/resources/` | `visualizer.js` (PixiJS 8 app), `pixi.min.js` (bundled locally, no CDN) |

---

## Plugin System

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`) is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped @CaseType("starcraft-game")` bean — no wiring changes elsewhere.

### R&D Framework Assignments

| Task | Class | R&D Framework | Approach |
|---|---|---|---|
| `StrategyTask` | `DroolsStrategyTask` | Drools 10.1.0 Rule Units | Forward-chaining DRL rules write `STRATEGY` key to CaseFile; Java dispatches intents. Hot-reloadable. Native-safe via Executable Model. |
| `EconomicsTask` | `FlowEconomicsTask` | Quarkus Flow | Per-tick Flow instance via `@Incoming` + `startInstance(tick)`. Single `consume()` step calls all four decisions sequentially — required to prevent `ResourceBudget` reset between steps (GE-0059). |
| `TacticsTask` | `DroolsTacticsTask` | Drools + custom Java GOAP | Drools classifies unit groups (rule phase 1); Java A* finds cheapest action plan per group (rule phase 2). First action dispatched as `AttackIntent`/`MoveIntent`. |
| `ScoutingTask` | `BasicScoutingTask` | Drools CEP + Java-managed buffers | Fresh `RuleUnitInstance` per tick from Java `Deque` buffers. Avoids Drools Fusion STREAM mode incompatibility with drools-quarkus extension. |

`BasicStrategyTask` is retained as a plain (non-CDI) class: reference implementation and direct-instantiation test target.

Plugins are registered at startup by `QuarkMindTaskRegistrar` — injecting each seam interface keeps Arc from removing the beans as unused (Arc's dead-bean elimination previously silently kept the registry empty).

### Plugin Framework Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Orchestration framework | CaseHub (Blackboard/CMMN) | Case file, task lifecycle, reactive control loop | Custom scheduler, Kogito |
| StrategyTask framework | Drools 10 forward-chaining rules | Declarative rules, hot-reload, native-safe Executable Model | Jadex BDI (GPL-3.0), Jason (no Maven Central) |
| EconomicsTask framework | Quarkus Flow (CNCF Serverless Workflow) | Stateful workflow, Quarkus-native, LangChain4j bridge | SmallRye reactive pipeline, Temporal |
| TacticsTask framework | Drools + custom Java GOAP planner | Native-safe, no external dep, Drools already in stack; `gdx-ai` is JVM-only | gdx-ai behaviour trees |
| ScoutingTask framework | Drools rule units + Java-managed CEP buffers | Avoids Drools Fusion STREAM mode incompatibility with Quarkus rule unit model | Drools Fusion `window:time()` (requires KieSession + kmodule.xml) |
| Drools inter-phase signalling | `DataStore<String>` (not `List<String>`) | DataStore insertions trigger RETE re-evaluation; plain List mutations don't (GE-0109) | `eval(list.stream()...)` in Phase 2 LHS — silently never fires |
| Plugin deactivation | `@Alternative` on inactive CDI bean | Arc deactivates `@Alternative` beans without beans.xml or `@Priority` on the replacement | Deleting old implementation |
| Flow input type | Immutable `GameStateTick` record | Jackson cannot serialize plain mutable classes (GE-0060); records work natively | Mutable POJO |
| Flow integration | `@Incoming` bridge + `startInstance(tick)` | `listen` task only accepts CloudEvents; in-memory channel carries plain POJOs | `listen` task (silent — never fires, GE-0061) |
| Flow step collapse | Single `consume()` step, all four decisions | Quarkus Flow serialises `GameStateTick` between `consume()` steps — resetting `ResourceBudget` each time; collapsed = one serialisation boundary | Four separate `consume()` steps (budget reset per step — broken) |
| GOAP role of Drools | Action compiler — fires once per tick, produces `GoapAction` list | No session cloning per A* node; one session per tick | Per-node oracle (session cloning) |
| Intent interface | Sealed | Compiler enforces switch exhaustiveness; new intent type can't silently fall through to `default` no-op | Open with `default` warn-and-skip |

---

## Mock Infrastructure

`SimulatedGame` is the scripted test oracle — updated whenever real SC2 surprises us. `EmulatedGame` is a physics simulation engine for development without a live SC2 binary. They are kept separate: mixing physics into `SimulatedGame` would corrupt its determinism.

| Class | Role |
|---|---|
| `SimulatedGame` | Hand-crafted stateful SC2 simulation; CDI bean in `%mock` profile |
| `ReplaySimulatedGame` | Replay-driven variant; plain Java, driven from real `.SC2Replay` tracker events (PlayerStats, UnitBorn, UnitDied, UnitInit, UnitDone) |
| `ReplayEngine` | `SC2Engine` for `%replay` profile — observe-only, records agent intents |
| `EmulatedGame` | Physics simulation engine: mineral harvesting, build times, movement, combat (E1-E3 complete); CDI bean in `%emulated` profile |
| `EmulatedEngine` | `SC2Engine` wrapping `EmulatedGame`; active on `@IfBuildProfile("emulated")` |
| `ScenarioLibrary` | Named test scenarios (set-resources, spawn-enemy-attack, etc.) |
| `SC2Data` | Shared constants — see §Domain Model |

### Emulation Engine Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `EmulatedGame` separate from `SimulatedGame` | `EmulatedGame` in `sc2/emulated/` | `SimulatedGame` is the scripted test oracle; mixing physics corrupts its determinism | Evolve SimulatedGame in-place |
| `SC2Data` in `domain/` | Shared constants for both engines | Eliminates drift between SimulatedGame and EmulatedGame data tables | Duplicate tables in each engine |
| `@IfBuildProfile("emulated")` on `EmulatedEngine` | Positive guard — active only in `%emulated` | Prevents CDI ambiguity without growing other engines' exclusion lists | Add "emulated" to all other `@UnlessBuildProfile` lists |

### Emulation Engine Progress

| Stage | What | Status |
|---|---|---|
| E1 | Unit movement, probe mineral harvesting, build times, `EmulatedGame`/`EmulatedEngine` infrastructure | ✅ Complete |
| E2 | Vector-based movement, scripted enemy wave at frame 200, full intent handling, cost deduction, `EmulatedConfig` live config panel | ✅ Complete |
| E3 | Shields/maxShields on `Unit`, two-pass simultaneous combat resolution, `SC2Data.damagePerTick`/`attackRange`/`maxShields`, unit death | ✅ Complete |
| E4 | Enemy active AI — enemy economy, production, attack waves | 🔜 Next |
| E5 | Pathfinding + terrain — A* on tile map, units navigate obstacles | 🔜 Planned |

### Combat Model (E3)

Two-pass simultaneous combat resolution prevents order-dependency:
1. **Collect phase**: for each unit in `attackingUnits`, accumulate damage into `Map<String, Integer>` (tag → total damage)
2. **Apply phase**: subtract from health (and shields first for Protoss), remove units at HP ≤ 0

`attackingUnits` is a `Set<String>` (unit tags) populated by `AttackIntent`. A `MoveIntent` does **not** clear it — SC2 semantics: move-only commands don't cancel auto-attack. A unit on retreat continues firing; E4 will add an explicit cancel path.

---

## Visualizer

A PixiJS 8 live visualizer renders game state each tick, served by Quarkus over WebSocket, wrapped in an Electron native window.

| Component | Role |
|---|---|
| `GameStateBroadcaster` | `SC2Engine` frame listener; pushes JSON game state to all WebSocket clients on each tick |
| `SpriteProxyResource` | Server-side Liquipedia sprite fetch, re-served to browser (CORS bypass for WebGL texture loading) |
| `visualizer.js` | PixiJS 8 application: unit sprites, health tinting (yellow→red), death removal |
| `pixi.min.js` | Bundled locally in `META-INF/resources/` — no CDN dependency, works offline |
| `electron/main.js` | Spawns Quarkus as subprocess, health-polls until ready, opens OS window |

**Health tinting:** `healthTint()` writes to both the PixiJS `Container` and the inner `Sprite` from `makeUnitSprite()` — dual-locus write required because PixiJS 8 Container tint propagates but the inner Sprite tint is a separate locus.

**Canvas testing:** `window.__test` semantic API exposed from `visualizer.js` — PixiJS renders to WebGL canvas with no DOM selectors; semantic assertions survive visual changes (preferred over screenshot pixel comparison).

### Visualizer Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| WebSocket push | `GameStateBroadcaster` as frame listener | Zero latency — pushed on each tick; no wasted polls | `setInterval(fetch)` polling |
| Sprite source | Liquipedia via `SpriteProxyResource` | No binary bloat in git; CORS enforcement prevents direct browser fetch | Download sprites to git |
| PixiJS bundled locally | `pixi.min.js` in `META-INF/resources/` | No CDN dependency; works offline | CDN link in HTML |
| Electron wraps Quarkus | `main.js` with health poll | Single native window; Quarkus lifecycle managed by Electron | Separate terminal windows |

---

## Real SC2 Integration

`ActionTranslator` — a pure static class mirroring `ObservationTranslator` — converts the `IntentQueue` drain into `ResolvedCommand` records. `SC2BotAgent.onStep()` applies them via the ocraft `ActionInterface`.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `translate()` returns `List<ResolvedCommand>` | Pure function — testable without mocking | `ActionInterface` has 12+ overloads; returning data eliminates any mocking framework | Call `ActionInterface` directly in translator |
| `ResolvedCommand` package-private | Only `ActionTranslator` + `SC2BotAgent` interact | No reason to expose beyond `sc2.real` | Public record |
| Tag-based dispatch | `ActionInterface.unitCommand(Tag, ...)` | Dead/stale tags silently ignored by SC2; no `UnitInPool` lookup needed | Look up `UnitInPool` via `observation()` |
| `casehub-persistence-memory` as runtime dep | CaseHub split into core + persistence modules | `CaseEngine` injects `TaskRepository`/`CaseFileRepository` from persistence module | Bundle everything in casehub-core |
| `quarkus.index-dependency` for persistence jar | No Jandex in casehub-persistence-memory jar | Quarkus skips CDI scanning of jars without `META-INF/jandex.idx` | Rebuild CaseHub with Jandex plugin |

---

## Core Agent Loop Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Engine abstraction | Single `SC2Engine` seam (replaces 3 interfaces) | All three always move together; one injection point | Keep 3 separate seams |
| Replay mode | `ReplayEngine` observe-only; `dispatch()` records intents | Replay is immutable; intents logged for offline evaluation | Apply intents to shadow simulation |
| Resource arbitration | `ResourceBudget` in CaseFile, consumed by plugins | Prevents double-spend without inter-plugin communication | Check raw minerals; accept over-commit |
| Build times | `PendingCompletion` with `completesAtTick`; buildings appear as `isComplete=false` immediately | Plugins need to see under-construction buildings; supply granted on completion | 1-tick instant or per-building queues |
| Active scouting | `@ApplicationScoped` state tracking scout probe tag | Singleton CDI bean state persists across ticks | CaseFile key per tick (doesn't persist) |
| Mock auto-start | `MockStartupBean` with `@UnlessBuildProfile(anyOf = {"sc2","replay","test","prod"})` | Mirrors SC2StartupBean/ReplayStartupBean pattern; `anyOf` undocumented but works | Require manual POST /sc2/start |

---

## Quarkus Profiles

| Profile | SC2 needed | Active beans |
|---|---|---|
| `%mock` (default) | No | `SimulatedGame`, `MockSC2Client`, `MockGameObserver`, `MockCommandDispatcher` |
| `%emulated` | No | `EmulatedGame`, `EmulatedEngine`; visualizer WebSocket active |
| `%sc2` | Yes | `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent` |
| `%replay` | No | `ReplayEngine` — full agent loop against a `.SC2Replay` file; `dispatch()` is observe-only |
| `%test` | No | Same as mock; scheduler disabled |
| `%prod` | — | QA endpoints stripped (`@UnlessBuildProfile("prod")`) |

---

## Key Dependencies

| Dependency | Purpose | Native? |
|---|---|---|
| `casehub-core` + `casehub-persistence-memory` | Blackboard/CMMN engine (local Maven install) | TBD |
| `drools-quarkus` + `drools-ruleunits-api/impl` | Drools 10.1.0 — Rule Units, AOT via Executable Model | ✅ Native-compatible |
| `quarkus-flow` | CNCF Serverless Workflow — per-tick economics flow | ✅ Quarkus-native |
| `ocraft-s2client` | SC2 protobuf API client | ❌ JVM-only — tracked in NATIVE.md |
| `scelight-mpq` + `scelight-s2protocol` | SC2 replay parsing (local fork) | ❌ JVM-only — tracked in NATIVE.md |
| `pixi.min.js` | PixiJS 8 WebGL renderer — bundled in `META-INF/resources/` | N/A (JS) |
| Playwright + Chromium | E2E canvas testing via `window.__test` semantic API | N/A (test only) |
| Electron | Native OS window wrapping Quarkus visualizer | N/A (desktop) |
| Quarkus 3.34.2 | Container, CDI, scheduler, REST, WebSocket | ✅ BOM |

---

## Testing Strategy

- **Unit tests** (`new`, no CDI): `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`, `EmulatedGameTest`
- **Integration tests** (`@QuarkusTest`, full CDI): `QaEndpointsTest`, `FullMockPipelineIT` — scheduler disabled, `orchestrator.gameTick()` called directly
- **Playwright E2E tests**: 9 render tests verifying sprite counts, positions, health tinting, unit disappearance on death; inject state via `SimulatedGame.setUnitHealth()`/`removeUnit()`; use `window.__test` semantic API (not pixel comparison)
- **Benchmark tests** (`@Tag("benchmark")`, `mvn test -Pbenchmark`): excluded from normal runs; `AtomicReference<TickTimings>` in `AgentOrchestrator` exposes last tick's phase breakdown; baseline: 2ms mean plugin time (pre-E2)
- **Total: 236 tests** (as of E3 complete)

**Rules:**
- Never use `@QuarkusTest` for tests that can be plain JUnit
- Exception: Drools Rule Unit tests require `@QuarkusTest` — `DataSource.createStore()` is initialized by the Quarkus extension at build time and unavailable in plain JUnit (GE-0053). `DroolsStrategyTaskTest` injects `StrategyTask` and calls `execute(CaseFile)` directly.
- WebSocket tests: use `java.net.http.WebSocket` (built-in Java 11 client) — Tyrus standalone conflicts with Quarkus classloader

---

## Current State

E3 complete. QuarkMind:
- Connects to and issues commands in a live SC2 game (all four plugins, real unit/building tags, sealed Intent dispatch)
- Runs full agent loop against `EmulatedGame` with Protoss combat (shields, two-pass simultaneous resolution, health tinting, unit death)
- Renders live game state in a PixiJS 8 visualizer via WebSocket, wrapped in Electron
- 236 tests: unit + integration + Playwright E2E

## Next Steps

- **E4: Enemy active AI** — enemy economy (mineral harvesting, unit production) and attack waves, so the bot faces a real opponent. Before starting: brainstorm, create GitHub epic + child issues, write plan. Run `mvn test -Pbenchmark` and record the post-E3 baseline before changing anything.
- **E5: Pathfinding + terrain** — A* on tile map, units navigate obstacles
- **#13 Live SC2 smoke test** — blocked on SC2 availability
- **#14 GraalVM native image tracing** — blocked on #13
- **#16 Scouting CEP threshold calibration** — ROACH_RUSH ≥6, 3RAX ≥12, 4GATE ≥8/8 are R&D estimates; need replay data
- **Deferred visualizer work** — probe overlap fix, HTML mineral display, geyser sprite, time-based UI tests
- **LangChain4j experimental StrategyTask** — LLM-guided strategy as a fifth R&D integration (Phase 4+, Ollama local model); deferred until core emulation is stable
- **Intent dispatch quality** — no guard against dead unit tags or incomplete buildings; bot commands whatever tag the plugin supplies

---

## Open Questions

- `attackingUnits` is never cleared by a `MoveIntent` — a unit given a retreat command continues firing; E4 should add an explicit cancel path
- `ReplaySimulatedGame` uses `shields=0` for replay units — replay tracker events don't include instantaneous shield state
- Observer supply cost defaults to 2 in `SC2Data.supplyCost` (real SC2 value is 1) — minor data gap, no test coverage for Observer training
- Scouting CEP thresholds (#16) still need calibration against replay data
- `attackingUnits` set is never cleared except on unit death — semantics evolve in E4 when retreat becomes a deliberate combat action
- Expansion detection heuristic: "enemy unit > 50 tiles from main base" accuracy against real SC2 unknown
- GOAP goal assignment hot-reload — DRL enables it but never exercised in practice
- Playwright Chromium install in CI — currently requires manual install step
- `SC2Engine.tick()` ownership — who owns the tick loop when real SC2 is connected? Open since Phase 0

---

## ADRs

| ADR | Decision |
|---|---|
| [ADR-0001 — Quarkus Flow placement](adr/0001-quarkus-flow-placement.md) | Per-tick stateful plugin (Option A) — exercises Flow's stateful workflow model; Drools/Flow signal boundary clean |

**ADR candidates (not yet written):**
- Two-pass simultaneous combat resolution vs sequential
- `attackingUnits` Set vs `unitTargets` semantics
- Quarkus Flow single-step pattern for shared mutable budget state
- `SC2Data` placement in `domain/` as shared engine constants
- `EmulatedGame`/`SimulatedGame` separation

**Deferred (not yet designed):**
- `HttpSC2Engine` — network bridge; SC2 on one machine, agent on another (Phase 4)
- Mineral collection model — worker-saturation curve replacing flat +5/tick trickle
