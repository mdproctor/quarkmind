# StarCraft II Quarkus Agent — Design Spec

**Date:** 2026-04-06  
**Status:** Approved for implementation planning  
**Purpose:** R&D testbed for Drools, Quarkus Flow, and CaseHub/CMMN — not a faithful reimplementation of any specific paper.

---

## 1. Goals

Build a Quarkus application that plays StarCraft II games (Protoss race), structured as a plugin platform. The agent's intelligence is entirely provided by swappable plugins — the platform provides the scaffolding, the SC2 connection, and the control loop. Real logic is filled in incrementally via plugin implementations.

**Primary drivers:**
- Evolve **Drools** (rule engine) in a realistic, demanding domain
- Evolve **Quarkus Flow** (workflow system) with long-running agentic tasks
- Evolve **CaseHub** (Blackboard/CMMN framework) as the core orchestrator
- Use StarCraft II as a live integration test harness for all three

**Secondary goal:** Eventually run in native Quarkus mode. JVM mode first for rapid iteration.

---

## 2. Architectural Inspiration

The GDA paper ("Applying Goal-Driven Autonomy to StarCraft", Weber & Mateas 2010) is used as structural reference for the plugin seam boundaries only — not for logic to reimplement. The GDA concerns (strategy, economics, tactics, scouting) map naturally to plugin slots. The CaseHub CaseFile serves as the blackboard equivalent.

---

## 3. Architecture

### 3.1 Single-Module Structure (Approach C)

One Quarkus module with clear package boundaries. Modules are extracted when a plugin implementation matures enough to warrant isolation. A `MODULES.md` documents the intended future split to prevent drift.

```
starcraft/
  pom.xml
  MODULES.md           # documents intended future module splits
  NATIVE.md            # tracks native Quarkus compatibility per dependency
  src/main/java/org/acme/starcraft/
    sc2/               # CDI interfaces — the SC2 contract
    sc2/mock/          # stateful mock implementation (default, no SC2 needed)
    sc2/real/          # ocraft-s2client implementation (requires SC2)
    domain/            # game state model (plain Java)
    agent/             # CaseHub intelligence layer + plugin SPIs
    plugin/            # default (dummy) plugin implementations
    qa/                # QA REST endpoints (dev/test profiles only)
  src/main/resources/
    application.properties
```

### 3.2 SC2 Interface Layer

The `sc2` package defines **CDI interfaces** only — no implementation. This is the contract between the SC2 world and the rest of the application. Two implementation families exist behind it; the intelligence layer is unaware of which is active.

```java
SC2Client        // connect / join game / leave game lifecycle
GameObserver     // produces GameState each decision cycle
CommandDispatcher // drains IntentQueue → SC2 commands each frame
```

**Profile switching:**
- `%mock` (default) — `sc2.mock` beans active. No SC2 installation needed.
- `%sc2` — `sc2.real` beans active. Requires SC2 installed and running.
- `%test` — `sc2.mock` beans active + scripted scenario injection via `ScenarioLibrary`.

### 3.3 sc2.mock — Stateful Mock (Living Specification)

The mock is not a simple stub — it is a **stateful simulation** of a Protoss game that receives commands and mutates its own state accordingly. It grows smarter over time as SC2 quirks are discovered and encoded.

**Components:**
- `MockSC2Client` — simulates connect/join/leave lifecycle
- `MockGameObserver` — generates `GameState` from `SimulatedGame` each cycle
- `MockCommandDispatcher` — applies typed intents to `SimulatedGame`, mutating its state
- `SimulatedGame` — internal game state: units, positions, resources, supply, build queue, game clock
- `ScenarioLibrary` — named mutations applied to `SimulatedGame` for QA and testing (e.g. `spawn_enemy_attack`, `set_resources_500`, `supply_almost_capped`, `enemy_expands`)

**The living specification principle:**

Every time real SC2 produces unexpected behaviour — a bug, an undocumented quirk, a silent command failure, a timing edge case — the process is:

1. Reproduce the issue against the mock
2. Update `SimulatedGame` rules or add a `ScenarioLibrary` entry to replicate the behaviour
3. Write a test that would have caught it
4. CI now catches this class of problem permanently — without needing SC2

The mock is never "done". It accumulates knowledge of SC2's actual behaviour, including workarounds, over the lifetime of the project.

### 3.4 sc2.real — ocraft-s2client Implementation

Real SC2 integration. Active under `%sc2` profile.

**Components:**
- `RealSC2Client` — ocraft-s2client connection and game lifecycle (`com.github.ocraft:ocraft-s2client-bot:0.4.21`)
- `RealGameObserver` — translates SC2 protobuf `Observation` → `GameState`
- `RealCommandDispatcher` — translates typed `Intent` objects → SC2 protobuf commands

**SC2 Debug API:** ocraft-s2client exposes SC2's debug interface (spawn units, set resources, draw on screen, etc.). The same named scenarios from `ScenarioLibrary` are implemented against the debug API for integration parity:
- `MockScenarioRunner` → mutates `SimulatedGame`
- `SC2DebugScenarioRunner` → calls SC2 Debug API on a live game

This allows the same QA scenarios to be run against both the mock and real SC2, verifying they produce equivalent behaviour.

### 3.5 Other Package Responsibilities

**`domain` package — Game State Model**
- Plain Java objects with no framework dependencies (native-safe by default)
- `GameState` — master snapshot assembled each decision cycle
- `Unit`, `Building`, `Resource` — Protoss-specific domain entities
- `GameMap` — terrain, choke points, expansion locations
- `PlayerInfo` — our state and observed enemy state

**`agent` package — CaseHub Intelligence Layer**
- `StarCraftCaseFile` — typed wrapper around CaseHub `CaseFile` with namespaced key constants
- `GameStateTranslator` — translates `GameState` into `CaseFile` writes each cycle; triggers CaseEngine
- `IntentQueue` — thread-safe buffer of typed `Intent` objects (`BuildIntent`, `AttackIntent`, `MoveIntent`, etc.)
- Plugin seam interfaces (see Section 4)
- CaseHub `CaseEngine` — control loop; drives plugin activation

**`plugin` package — Default Implementations**
- Dummy `@ApplicationScoped` CDI beans implementing each plugin seam
- Activated by default; replaced by alternative CDI beans (via Quarkus profiles or `@Priority`) as R&D progresses

**`qa` package — QA REST Endpoints**
- Active under `%dev` and `%test` profiles only — stripped from production builds
- See Section 5 for full endpoint list

### 3.6 Two-Loop Runtime Model

The SC2 frame loop and the CaseEngine reasoning loop are fully decoupled. Neither blocks the other.

```
SC2 frame arrives (real or mock)
  → GameObserver notifies Level 1 (Frame Observer) plugins
  → GameObserver builds GameState
  → GameStateTranslator writes GameState to CaseFile
      → CaseFile.onChange() notifies Level 2 (CaseFile Reactor) plugins
      → CaseEngine wakes, evaluates eligible TaskDefinitions
          → Level 3 (PlanningStrategy) plugins direct CaseEngine
          → Level 4 (TaskDefinition/Worker) plugins execute
          → Plugins write typed Intents to IntentQueue
  → CommandDispatcher drains IntentQueue → commands sent (to mock or real SC2)
```

`CaseFile` and `IntentQueue` are the shared substrate — passive data structures readable and writable by any plugin at any level.

---

## 4. Plugin Model

### 4.1 Four Plugin Levels

Plugins participate at different levels depending on what they need to observe, direct, or interact with. A plugin is not limited to one level — a Drools implementation might participate at both Level 2 (watching the CaseFile) and Level 3 (directing the CaseEngine).

| Level | Hook point | Timing | Suited for |
|---|---|---|---|
| **1 — Frame Observer** | Registered listener on `GameObserver` | Every SC2 frame (~22fps) | Raw ML inference, frame-accurate monitors, emergency interrupts |
| **2 — CaseFile Reactor** | `CaseFile.onChange()` listener | On any CaseFile write | Drools watching the full blackboard, discrepancy detectors, cross-concern rules |
| **3 — Planning Strategy** | CaseHub `PlanningStrategy` | Between CaseEngine cycles | Strategy selection, crisis response, suspending/prioritising TaskDefinitions, CMMN case plan manipulation |
| **4 — TaskDefinition / Worker** | CaseHub `TaskDefinition` (called) or `Worker` (autonomous) | When entry criteria met, or autonomously | StrategyTask, EconomicsTask, TacticsTask, ScoutingTask — and their real implementations |

### 4.2 Coarse-Grained Plugin Seams (Level 4 defaults)

Four named plugin seams, each a CDI interface. Swap an implementation by providing a new `@ApplicationScoped` bean — no wiring changes.

```java
// Each extends CaseHub's TaskDefinition and declares its CaseFile contracts
public interface StrategyTask extends TaskDefinition { }
public interface EconomicsTask extends TaskDefinition { }
public interface TacticsTask extends TaskDefinition { }
public interface ScoutingTask extends TaskDefinition { }
```

Default implementations in `plugin` package:
- `PassThroughStrategyTask` — fixed no-op strategy
- `BasicEconomicsTask` — keep workers mining, avoid supply block (Phase 2)
- `PassThroughTacticsTask` — units idle
- `PassThroughScoutingTask` — no scouting

### 4.3 CaseFile Key Conventions

Keys are namespaced strings. The full key list is a TODO — it evolves with implementation. Proposed namespace structure (illustrative examples only):

```
game.resources.minerals        Integer   — current mineral count
game.resources.vespene         Integer   — current vespene gas count
game.resources.supply.used     Integer
game.resources.supply.cap      Integer
game.units.workers             List<Unit>
game.units.army                List<Unit>
game.buildings.nexuses         List<Building>
game.strategy.current          StrategyType  — written by StrategyTask
game.intel.enemy.units         List<Unit>    — written by ScoutingTask
game.intel.enemy.base          Point2d       — written by ScoutingTask
game.intent.crisis             Boolean       — written by any level 2/3 plugin to signal urgency
```

Convention: `game.*` for SC2 observation state; `agent.*` for plugin-written reasoning state.

---

## 5. QA Harness

Three axes of QA exercisable even with all-dummy plugins. All endpoints are `%dev`/`%test` profile only — stripped from production.

### Observe — verify what the bot sees

| Endpoint | Purpose |
|---|---|
| `GET /sc2/casefile` | Live snapshot of full CaseFile state |
| `GET /sc2/casefile/stream` | SSE stream of CaseFile writes in real time |
| `GET /sc2/plugins` | Active plugins per level, activation counts, last-fired timestamp |

Structured logging on every CaseFile write and TaskDefinition activation (with game time) is always on.

### Direct — verify commands reach SC2

| Endpoint | Purpose |
|---|---|
| `POST /sc2/intent` | Inject a typed intent directly into IntentQueue — bypasses all plugins |
| `GET /sc2/intents/pending` | Intents queued but not yet dispatched |
| `GET /sc2/intents/dispatched` | Ring-buffer of last N dispatched intents |
| `GET /sc2/frame` | Current frame counter and tick rate — detect stalls |

### Interact — trigger scenarios, verify reactions

| Endpoint | Purpose |
|---|---|
| `POST /sc2/debug/scenario/{name}` | Trigger a named `ScenarioLibrary` entry against mock or SC2 Debug API |
| `POST /sc2/casefile/{key}` | Manually write a CaseFile key — test plugin reactions without running game logic |

**Step mode:** SC2 API supports advancing N frames on demand. Enables deterministic integration tests without real-time timing pressure.

---

## 6. Cross-Cutting Concerns

### 6.1 Threading & Timing Bridge
`CaseFile` and `IntentQueue` are the only shared-state hand-off points between the two loops. Both are thread-safe. The SC2 frame callback never waits on CaseEngine; CaseEngine never blocks on SC2. This holds equally for mock and real implementations.

### 6.2 Native Quarkus Compatibility
- All CDI injection via constructor or field annotations — no programmatic `Arc` lookups
- No dynamic class loading or runtime code generation in application code
- Reflection usages registered in `src/main/resources/reflection-config.json`
- `NATIVE.md` at project root tracks per-dependency native status (ocraft-s2client uses RxJava + Protobuf — native verification required before Phase 2)
- Plugin module extraction is required before native build (separate artifact per plugin)

### 6.3 Race
**Protoss.** Simplest production model — no Zerg larva/morph mechanics, no Terran building lift/reactor complexity. Domain model starts small and extends naturally.

### 6.4 Action Conflict Resolution
Typed intents carry unit ownership — each unit can only have one active intent. `PlanningStrategy` (Level 3) arbitrates if multiple plugins claim the same unit. Default priority: Tactics > Economics > Strategy for unit commands.

### 6.5 Observability
Structured logging on every CaseFile write and every TaskDefinition activation (via CaseHub's built-in logging). Quarkus Dev UI visible during development. Full OpenTelemetry wiring deferred to later phase.

---

## 7. Dependencies

| Dependency | Version | Purpose | Native? |
|---|---|---|---|
| `com.github.ocraft:ocraft-s2client-bot` | 0.4.21 | SC2 API client (`sc2.real` only) | TBD — verify in Phase 1 |
| CaseHub (`casehub-core`) | SNAPSHOT | CaseFile, CaseEngine, TaskDefinition, Worker | In-progress — track in NATIVE.md |
| Quarkus | 3.32.2 | Application framework, CDI, Dev UI, REST | Yes |

**CaseHub dependency:** Local Maven install for now (`mvn install` in `/Users/mdproctor/claude/alpha`). Will move to GitHub Packages then Maven Central as CaseHub matures.

**ocraft-s2client** is only required at runtime under the `%sc2` profile. The `%mock` profile has no dependency on it.

---

## 8. Phased Build Plan

Each phase produces a runnable system. No phase leaves it broken.

### Phase 0 — Mock Architecture
**Goal:** Full package and class structure in place. All four plugin seams wired with dummy implementations. Full QA harness running. Everything tested against `SimulatedGame`. No SC2 needed.  
**Delivers:**
- All four packages (`sc2`, `domain`, `agent`, `plugin`)
- `sc2` CDI interfaces defined
- `sc2.mock`: `MockSC2Client`, `MockGameObserver`, `MockCommandDispatcher`, `SimulatedGame`, `ScenarioLibrary` (initial set of scenarios)
- CaseHub integrated, `GameStateTranslator` (stub), `IntentQueue` wired
- Four plugin seam interfaces + four dummy implementations
- `qa` package: all observe/direct/interact REST endpoints
- Full unit and integration test suite running against mock
- `NATIVE.md` started  

**Done when:** CaseEngine cycles with dummy plugins against `SimulatedGame`, QA endpoints working, all scenarios in `ScenarioLibrary` exercised by tests.

### Phase 1 — SC2 Bootstrap
**Goal:** Plug in real SC2. Prove the real implementation matches the mock contract. Validate QA harness works against a live game.  
**Delivers:**
- `sc2.real`: `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`
- `SC2DebugScenarioRunner` implementing the same `ScenarioLibrary` scenarios against SC2 Debug API
- `%sc2` profile wiring
- ocraft-s2client native compatibility assessed, `NATIVE.md` updated  

**Done when:** Bot connects to SC2, joins a game, full pipeline runs, QA endpoints work against live SC2, `ScenarioLibrary` scenarios run via debug API.

### Phase 2 — Survive
**Goal:** Bot plays a full game without crashing. Basic Protoss economy running.  
**Delivers:** `GameStateTranslator` fills CaseFile with real data, `BasicEconomicsTask` with real logic (probes, pylons, nexus saturation), Protoss unit types in domain model, corresponding `SimulatedGame` rules updated.  
**Done when:** Bot plays a full game, economy runs, doesn't supply-block itself.

### Phase 3 — Fight
**Goal:** Replace one dummy plugin with a first real R&D implementation. Bot can beat easiest built-in AI.  
**Delivers:** First non-dummy plugin (Drools, Quarkus Flow, or CaseHub PlanningStrategy), CDI profile-based plugin swap proven, cross-level plugin interaction exercised.  
**Done when:** First real plugin runs, plugin swap mechanism proven end-to-end.

### Phase 4+ — Evolve
Progressively replace dummy plugins. Add cross-level plugins. Push toward harder built-in AI difficulties. Extract Maven modules as implementations mature. Verify native Quarkus build. Continuously grow `SimulatedGame` fidelity as SC2 quirks are discovered.

---

## 9. What This Is Not

- Not a faithful reimplementation of EISBot or the GDA paper
- Not a fixed architecture — plugin granularity is intentionally left open
- Not a single-technology solution — Drools, Quarkus Flow, and CaseHub are all intended to coexist as alternative/complementary plugin implementations
- Not a static test suite — `SimulatedGame` is a living specification that grows with real-world SC2 knowledge
