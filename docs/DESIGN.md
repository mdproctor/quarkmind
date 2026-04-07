# StarCraft II Quarkus Agent — Design

## Overview

A Quarkus application that plays StarCraft II (Protoss) as a plugin platform.
Primary purpose is R&D: a living testbed for Drools, Quarkus Flow, and CaseHub
(a Blackboard/CMMN framework). The platform provides scaffolding, SC2
connection, and the CaseHub control loop; intelligence is provided by swappable
plugins behind CDI seams.

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
```

The game loop fires once per Quarkus Scheduler tick. Each tick:
1. `SC2Engine.tick()` advances the internal clock (mock only; no-op for real SC2)
2. `SC2Engine.observe()` returns the current `GameState`
3. `GameStateTranslator` converts it to a CaseHub CaseFile map
4. `AgentOrchestrator` cycles the CaseEngine; plugins fire and produce `Intent` objects
5. `SC2Engine.dispatch()` flushes the `IntentQueue` to the game

---

## Domain Model

Plain Java records in `domain/` — no framework dependencies, always
native-compatible.

| Record | Purpose |
|---|---|
| `GameState` | Snapshot: minerals, vespene, supply, unit lists, game frame |
| `Unit` | Single unit: tag, type, position, health |
| `Building` | Single building: tag, type, position, health, isComplete |
| `UnitType` | Enum: PROBE, ZEALOT, STALKER, IMMORTAL, COLOSSUS, CARRIER, etc. |
| `BuildingType` | Enum: NEXUS, PYLON, GATEWAY, CYBERNETICS_CORE, etc. |
| `Point2d` | Map coordinate |

---

## Component Structure

| Package | Responsibility |
|---|---|
| `domain/` | Plain Java records — no CDI, no framework imports |
| `sc2/` | CDI interfaces: `SC2Engine` (unified engine seam), `ScenarioRunner`, `IntentQueue` |
| `sc2/intent/` | Intent types: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent` |
| `sc2/mock/` | Mock implementation: `SimulatedGame`, `MockGameObserver`, `MockCommandDispatcher` |
| `sc2/mock/scenario/` | `ScenarioLibrary` — living specification of SC2 behaviour |
| `sc2/real/` | Real SC2 implementation via ocraft-s2client (active on `%sc2` profile) |
| `agent/` | `AgentOrchestrator`, `GameStateTranslator`, `StarCraftCaseFile` (key constants) |
| `agent/plugin/` | Plugin seam interfaces: `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| `plugin/` | Real plugin implementations: `BasicEconomicsTask`, `BasicScoutingTask`, `BasicTacticsTask`, `DroolsStrategyTask` |
| `plugin/drools/` | Drools Rule Unit: `StrategyRuleUnit` (data context) + `StarCraftStrategy.drl` (rules) |
| `agent/StarCraftTaskRegistrar` | Startup bean wiring all four plugin seams into `TaskDefinitionRegistry` |
| `qa/` | QA REST endpoints — dev/test only (`@UnlessBuildProfile("prod")`) |

---

## Plugin System

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`)
is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation
by providing a new `@ApplicationScoped @CaseType("starcraft-game")` bean — no
wiring changes elsewhere.

All four plugin seams have real implementations:

| Task | Class | Approach |
|---|---|---|
| `EconomicsTask` | `BasicEconomicsTask` | Probe production (target 22), Pylon supply management (headroom ≤ 4) |
| `StrategyTask` | `DroolsStrategyTask` | Drools 10.1.0 Rule Units — DRL rules write decisions; Java dispatches intents |
| `TacticsTask` | `BasicTacticsTask` | Reads `STRATEGY` key; AttackIntent/MoveIntent dispatch |
| `ScoutingTask` | `BasicScoutingTask` | Passive intel accumulation and active probe scouting |

`BasicStrategyTask` is retained as a plain (non-CDI) class: reference implementation and direct-instantiation test target for the Drools strategy logic.

Plugins are registered with CaseHub at startup by `StarCraftTaskRegistrar` — injecting each seam interface keeps Arc from removing the beans as unused (Arc's dead-bean elimination previously silently kept the registry empty).

Four plugin levels (outermost to innermost):
1. **Frame** — per-tick wrapper
2. **CaseFile** — reads/writes CaseHub blackboard
3. **PlanningStrategy** — selects active plan
4. **TaskDefinition** — executes a specific task (the four plugin seams)

---

## Mock Infrastructure

`SimulatedGame` is the living specification of SC2 behaviour — updated whenever
real SC2 surprises us. `ReplaySimulatedGame` extends `SimulatedGame` to drive
state from real `.SC2Replay` tracker events (PlayerStats, UnitBorn, UnitDied,
UnitInit, UnitDone) rather than the hand-crafted economic trickle.

| Class | Role |
|---|---|
| `SimulatedGame` | Hand-crafted stateful SC2 simulation; CDI bean in `%mock` profile |
| `ReplaySimulatedGame` | Replay-driven variant; plain Java, used directly in tests |
| `ReplayEngine` | `SC2Engine` for `%replay` profile — observe-only, records agent intents |
| `ScenarioLibrary` | Named test scenarios (set-resources, spawn-enemy-attack, etc.) |

---

## Quarkus Profiles

| Profile | SC2 needed | Active beans |
|---|---|---|
| `%mock` (default) | No | `SimulatedGame`, `MockSC2Client`, `MockGameObserver`, `MockCommandDispatcher` |
| `%sc2` | Yes | `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent` |
| `%test` | No | Same as mock; scheduler disabled |
| `%replay` | No | `ReplayEngine` — full agent loop against a `.SC2Replay` file; `dispatch()` is observe-only |
| `%prod` | — | QA endpoints stripped (`@UnlessBuildProfile("prod")`) |

---

## Key Dependencies

| Dependency | Purpose | Native? |
|---|---|---|
| `casehub-core` | Blackboard/CMMN engine (local Maven install) | TBD |
| `drools-quarkus` + `drools-ruleunits-api/impl` | Drools 10.1.0 (Apache KIE) Rule Units — AOT rule compilation via Executable Model | ✅ Native-compatible |
| `ocraft-s2client` | SC2 protobuf API client | No (JVM-only) — tracked in NATIVE.md |
| `scelight-mpq` + `scelight-s2protocol` | SC2 replay parsing (local fork) | No — tracked in NATIVE.md |
| Quarkus 3.34.2 | Container, CDI, scheduler, REST | Yes (BOM) |

---

## Testing Strategy

- **Unit tests** (`new`, no CDI): `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`
- **Integration tests** (`@QuarkusTest`, full CDI): `QaEndpointsTest`, `FullMockPipelineIT` — scheduler disabled, `orchestrator.gameTick()` called directly
- Never use `@QuarkusTest` for tests that can be plain JUnit
- Exception: Drools Rule Unit tests require `@QuarkusTest` — `DataSource.createStore()` is initialized by the Quarkus extension at build time and is unavailable in plain JUnit (GE-0053). `DroolsStrategyTaskTest` is the canonical example; it injects `StrategyTask` and calls `execute(CaseFile)` directly.
