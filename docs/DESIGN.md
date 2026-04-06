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
1. `GameObserver` reads raw SC2 state
2. `GameStateTranslator` converts it to `GameState` (domain records)
3. `AgentOrchestrator` writes state into the CaseHub CaseFile and cycles the engine
4. Plugins fire, produce `Intent` objects (build/train/attack/move)
5. `CommandDispatcher` dispatches queued intents to SC2

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
| `sc2/` | CDI interfaces: `SC2Client`, `GameObserver`, `CommandDispatcher`, `ScenarioRunner` |
| `sc2/intent/` | Intent types: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent` |
| `sc2/mock/` | Mock implementation: `SimulatedGame`, `MockGameObserver`, `MockCommandDispatcher` |
| `sc2/mock/scenario/` | `ScenarioLibrary` — living specification of SC2 behaviour |
| `sc2/real/` | Real SC2 implementation via ocraft-s2client (active on `%sc2` profile) |
| `agent/` | `AgentOrchestrator`, `GameStateTranslator`, `StarCraftCaseFile` (key constants) |
| `agent/plugin/` | Plugin seam interfaces: `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| `plugin/` | Default (pass-through) plugin implementations — `PassThrough*Task` |
| `qa/` | QA REST endpoints — dev/test only (`@UnlessBuildProfile("prod")`) |

---

## Plugin System

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`)
is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation
by providing a new `@ApplicationScoped @CaseType("starcraft-game")` bean — no
wiring changes elsewhere.

`EconomicsTask` has a first real implementation (`BasicEconomicsTask`): probe
production (target 22 workers per base) and pylon supply management (build when
headroom ≤ 4 supply). `StrategyTask`, `TacticsTask`, and `ScoutingTask` remain
pass-through stubs.

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
| `ScenarioLibrary` | Named test scenarios (set-resources, spawn-enemy-attack, etc.) |

---

## Quarkus Profiles

| Profile | SC2 needed | Active beans |
|---|---|---|
| `%mock` (default) | No | `SimulatedGame`, `MockSC2Client`, `MockGameObserver`, `MockCommandDispatcher` |
| `%sc2` | Yes | `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent` |
| `%test` | No | Same as mock; scheduler disabled |
| `%prod` | — | QA endpoints stripped (`@UnlessBuildProfile("prod")`) |

---

## Key Dependencies

| Dependency | Purpose | Native? |
|---|---|---|
| `casehub-core` | Blackboard/CMMN engine (local Maven install) | TBD |
| `ocraft-s2client` | SC2 protobuf API client | No (JVM-only) — tracked in NATIVE.md |
| `scelight-mpq` + `scelight-s2protocol` | SC2 replay parsing (local fork) | No — tracked in NATIVE.md |
| Quarkus 3.34.2 | Container, CDI, scheduler, REST | Yes (BOM) |

---

## Testing Strategy

- **Unit tests** (`new`, no CDI): `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`
- **Integration tests** (`@QuarkusTest`, full CDI): `QaEndpointsTest`, `FullMockPipelineIT` — scheduler disabled, `orchestrator.gameTick()` called directly
- Never use `@QuarkusTest` for tests that can be plain JUnit
