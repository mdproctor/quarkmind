# SC2 Engine Architecture — Roadmap

**Created:** 2026-04-06  
**Status:** Living document — update as phases complete

---

## Vision

The platform evolves from "one hard-wired SC2 connection" to a **pluggable engine
system** where the same agent and plugin stack can run against any backend:
a hand-crafted simulation, a real replay, a Quarkus-native emulator, a local SC2
process, or a remote SC2 process over a network bridge. Swapping engines requires
no changes to plugins or the agent orchestrator.

---

## Current State (Phase 0 + 1 complete)

Three CDI seams cover the engine contract implicitly:

```
SC2Client        — lifecycle (connect / join / leave)
GameObserver     — observation (→ GameState)
CommandDispatcher — command dispatch (intents → SC2)
```

Two engine stacks exist, selected by Quarkus profile:

| Profile | Stack |
|---|---|
| `%mock` | `MockSC2Client` + `MockGameObserver` + `MockCommandDispatcher` → `SimulatedGame` |
| `%sc2` | `RealSC2Client` + `RealGameObserver` + `RealCommandDispatcher` → ocraft-s2client |

**Gap:** the three seams are conceptually one unit but are wired separately.
Swapping engines means three profile-guarded bean swaps instead of one.

---

## Phase 2 — Engine Abstraction (near term)

**Goal:** make the engine an explicit first-class seam.

Introduce `SC2Engine` as a single CDI interface encapsulating all three concerns:

```java
public interface SC2Engine {
    void connect();
    void joinGame();
    void tick();           // advance internal clock if engine owns timing
    GameState observe();   // replaces GameObserver
    void dispatch(List<Intent> intents);  // replaces CommandDispatcher
    void leaveGame();
    boolean isConnected();
}
```

`AgentOrchestrator` injects `SC2Engine` instead of three separate beans.
`IntentQueue` stays independent (it belongs to the agent layer, not the engine).

Existing implementations become:

| Name | Backed by | Replaces |
|---|---|---|
| `MockEngine` | `SimulatedGame` | Mock* trio |
| `RealSC2Engine` | ocraft-s2client | Real* trio |

**Milestone:** all existing tests pass unchanged; profile selection now controls
one bean instead of three.

---

## Phase 3 — ReplayEngine (near term, after Phase 2)

**Goal:** wire `ReplaySimulatedGame` into the engine system so it can drive the
full agent loop — plugins fire, decisions are made, intents are generated — even
though the intents cannot be applied back to the replay.

```java
public class ReplayEngine implements SC2Engine {
    // observe() → steps ReplaySimulatedGame forward
    // dispatch() → records intents but does not apply them (read-only game)
    // tick()     → drainEventsUpTo(currentLoop + LOOPS_PER_TICK)
}
```

**Use cases:**
- Train and evaluate plugins against real human/bot game data
- Verify that `BasicEconomicsTask` makes sensible decisions across 22 known replays
- Detect plugin regressions: replay the same game, check intent stream changes

**Limitation:** permanently read-only. `dispatch()` records what the agent
*would have done*, enabling offline evaluation but not closed-loop play.

**Profile:** add `%replay` Quarkus profile, or make the replay path configurable
at runtime (e.g. `starcraft.engine=replay`, `starcraft.replay.path=...`).

---

## Phase 4 — Network Bridge / HttpSC2Engine (medium term)

**Goal:** decouple the machine running SC2 from the machine running the
orchestrator.

```
[SC2 Machine — Windows/macOS]          [Orchestrator Machine — any]
  SC2 process                             Quarkus Agent
  ocraft-s2client                         HttpSC2Engine
  SC2AdapterService (thin Quarkus)  ←→    (REST or gRPC)
    /game/state  → GameState JSON
    /game/intent ← IntentList JSON
    /game/start, /game/stop
```

`SC2AdapterService` is a minimal Quarkus app on the SC2 machine — no plugins,
no CaseHub, just protocol translation. `HttpSC2Engine` on the orchestrator
implements `SC2Engine` over HTTP/gRPC.

**Benefits:**
- Orchestrator has no ocraft-s2client dependency; runs on Linux/containers
- SC2 machine can stay Windows
- Multiple orchestrators can connect to the same SC2 session for comparison
- The adapter boundary is a natural record/replay point (log all state+intent
  pairs for offline analysis)

**Note:** ocraft-s2client already supports remote hostnames, so for simple
cross-machine use just configure the hostname — skip the adapter until the
richer benefits are needed.

---

## Phase 5 — Quarkus EmulationEngine (long term)

**Goal:** a Quarkus-native SC2 emulator that can receive and apply commands,
closing the loop without a real SC2 process.

```java
public class EmulationEngine implements SC2Engine {
    // Full stateful SC2 simulation:
    //   - Realistic build times (game loops, not 1-tick queues)
    //   - Worker mineral collection model (trip times, saturation)
    //   - Parallel training queues (one per building)
    //   - Simple combat model (DPS × time, no pathfinding)
    //   - Map: buildability grid, basic terrain
    //   - Tech tree enforcement
}
```

**What it is NOT:**
- Not a full physics engine — no pathfinding, no unit acceleration, no splash geometry
- Not a fog-of-war simulator — mock mode sees everything
- Not a replacement for `%sc2` — real SC2 is still the ground truth

**Why it matters:**
- Full closed-loop agent training without SC2 licence or machine dependency
- Faster than real SC2 (run at 10× speed)
- Deterministic — reproducible training runs
- Can be fuzzed, property-tested, or run in CI

**Implementation layers** (can be built incrementally):

| Layer | Milestone |
|---|---|
| Realistic build times | `SimulatedGame` build queue uses game loop durations |
| Worker economics | Mineral trip model; saturation curve |
| Parallel training | Per-building queues |
| Simple combat | DPS model; unit death events |
| Map grid | Buildability matrix; spawn positions |
| Tech tree | Prerequisite enforcement |
| Pathfinding | Not planned — approximation sufficient |

---

## Engine Selection Summary

| Engine | Closed loop | Real data | SC2 needed | Phase |
|---|---|---|---|---|
| `MockEngine` | ✅ | ❌ hand-crafted | ❌ | Done |
| `ReplayEngine` | ❌ observe only | ✅ | ❌ | 3 |
| `RealSC2Engine` | ✅ | ✅ | ✅ | Done |
| `HttpSC2Engine` | ✅ | ✅ | remote | 4 |
| `EmulationEngine` | ✅ | ❌ simulated | ❌ | 5 |

---

## Architecture Invariant

**The agent orchestrator and all plugins are engine-agnostic.** They read
`GameState` and emit `Intent` objects. The engine is the only thing that knows
how to produce one and consume the other. Changing engines is a one-bean swap.

---

## Open Questions

- Should `SC2Engine` expose a `tick()` method, or should the Quarkus Scheduler
  always own timing (and engines just respond to `observe()` + `dispatch()` calls)?
  Current mock tick is engine-driven; real SC2 tick is scheduler-driven. Needs
  unifying.
- `ReplayEngine` profile: config-driven (`starcraft.engine=replay`) vs. a proper
  `%replay` Quarkus profile? Config is more flexible (swap at runtime); profile
  gives compile-time profile-guarded beans.
- `HttpSC2Engine` protocol: REST (simple, debuggable) vs. gRPC (efficient,
  streaming)? Streaming game state suggests gRPC, but REST is sufficient for
  500ms tick rates.
