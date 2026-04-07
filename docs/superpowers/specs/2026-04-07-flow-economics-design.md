# FlowEconomicsTask Design Spec

**Date:** 2026-04-07  
**Status:** Approved — ready for implementation  
**ADR:** `docs/adr/0001-quarkus-flow-placement.md`

---

## Overview

Replace `BasicEconomicsTask` with `FlowEconomicsTask` — a Quarkus Flow–backed
implementation of the `EconomicsTask` plugin seam. The Flow manages Protoss
economics as a stateful, multi-phase workflow that persists across the whole
game, receiving game-state tick events and writing intents to `IntentQueue`.

This is the second R&D integration target (after `DroolsStrategyTask`).

---

## Architecture

```
AgentOrchestrator
  └─ CaseEngine.createAndSolve() [per tick, synchronous]
       ├─ DroolsStrategyTask.execute(CaseFile)      ← writes signals to CaseFile
       └─ FlowEconomicsTask.execute(CaseFile)       ← emits tick event, returns immediately
            │
            │ SmallRye in-memory channel ("economics-ticks")
            │ CloudEvent type: org.acme.starcraft.economics.tick
            │ One-tick lag — accepted (500ms tick, invisible in play)
            ▼
       EconomicsFlow [long-lived, one instance per game]
            └─ loop: listen → supplyCheck → probeCheck → gasCheck → expansionCheck → phase transition → loop
                 └─ EconomicsDecisionService → IntentQueue (direct inject)
```

**Key structural facts:**
- `FlowEconomicsTask implements EconomicsTask` — CDI/CaseHub see no change at the seam
- `execute()` is a thin emitter; it returns before the flow processes anything
- One-tick lag is an explicit design decision (recorded in ADR-0001)
- Flow reads **Drools-written signals** from the tick payload for phase transitions, not raw
  game state directly. When Drools CEP lands, only the signal-writing side changes.

---

## New Components

| Component | Package | Type |
|---|---|---|
| `GameStateTick` | `domain/` | Record — plain Java, no CDI |
| `EconomicsContext` | `domain/` | Record — plain Java, no CDI |
| `EconomicsContext.Phase` | `domain/` | Enum |
| `FlowEconomicsTask` | `plugin/` | `@ApplicationScoped @CaseType("starcraft-game")` |
| `EconomicsFlow` | `plugin/flow/` | `@ApplicationScoped extends Flow` |
| `EconomicsDecisionService` | `plugin/flow/` | `@ApplicationScoped` CDI bean |
| `EconomicsLifecycle` | `plugin/flow/` | `@ApplicationScoped` CDI bean |
| `GameStarted` | `sc2/` | CDI event record |
| `GameStopped` | `sc2/` | CDI event record |

`BasicEconomicsTask` is demoted to a plain (non-CDI) class, retained for
direct-instantiation tests and as reference implementation — same pattern as
`BasicStrategyTask`.

---

## Event Bridge

`FlowEconomicsTask.execute(CaseFile)` extracts relevant game state into a
`GameStateTick` and emits it on the `economics-ticks` in-memory channel:

```java
record GameStateTick(
    int minerals, int vespene,
    int supplyUsed, int supplyCap,
    List<Unit> workers,
    List<Building> buildings,
    List<Resource> geysers,
    ResourceBudget budget,
    String strategy,     // from StarCraftCaseFile.STRATEGY (Drools-written)
    boolean gasReady     // true when Gateway exists; future: StarCraftCaseFile.SIGNAL_GAS_READY
    // Note: no `saturated` field — saturation depends on ctx.probeTarget(), which lives in the
    // flow's context and is not available in execute(). checkExpansion(tick, ctx) computes:
    //   tick.workers().size() >= ctx.probeTarget()
    // When Drools CEP lands, SIGNAL_SATURATED is added here as a CaseFile-sourced field.
) {}
```

- `gasReady`: computed in `execute()` as `buildings.stream().anyMatch(b -> b.type() == GATEWAY)`
- When Drools CEP is added, `gasReady` becomes a read of `StarCraftCaseFile.SIGNAL_GAS_READY`.
  The flow's `checkGas` condition check does not change.

---

## Workflow Phases

### Context model

```java
record EconomicsContext(
    Phase phase,
    int probeTarget,      // 22 × nexusCount
    int nexusCount,
    boolean gas1Built,
    boolean gas2Built
) {
    enum Phase { SATURATION, GAS_TRANSITION, EXPANDING, MULTI_BASE }
}
```

### Assimilator ownership transfer

Assimilator building moves from `DroolsStrategyTask` to `FlowEconomicsTask` —
it is an economic decision, not a strategic one. `DroolsStrategyTask`'s
"ASSIMILATOR" dispatch branch is removed as part of this implementation.
`FlowEconomicsTask.checkGas()` queues the assimilator build intents.

### Phase transitions

| From | Condition | To |
|---|---|---|
| `SATURATION` | `tick.gasReady()` | `GAS_TRANSITION` |
| `GAS_TRANSITION` | `ctx.gas1Built()` (1st assimilator confirmed in buildings) | `SATURATION` |
| `SATURATION` | `tick.workers().size() >= ctx.probeTarget()` | `EXPANDING` |
| `EXPANDING` | new Nexus detected in buildings | `MULTI_BASE` |
| `MULTI_BASE` | — | loops; `probeTarget = 22 × nexusCount` |

### Per-tick workflow loop

```
listen("tick", toOne("org.acme.starcraft.economics.tick"))
  → function("supplyCheck",     decisions::checkSupply,    GameStateTick.class)
  → function("probeCheck",      decisions::checkProbes,    GameStateTick.class)
  → function("gasCheck",        decisions::checkGas,       GameStateTick.class)
  → function("expansionCheck",  decisions::checkExpansion, GameStateTick.class)
  → function("phaseTransition", decisions::updatePhase,    GameStateTick.class)
      .exportAs((ctx, prev) -> merge(ctx, prev), EconomicsContext.class)
  → loop back to "tick"
```

Supply management (pylon check) runs every tick regardless of phase.

---

## Decision Service

`EconomicsDecisionService` is a plain CDI bean injected by the flow. It holds
all decision logic and has direct access to `IntentQueue`:

```java
@ApplicationScoped
class EconomicsDecisionService {
    @Inject IntentQueue intentQueue;

    void checkSupply(GameStateTick tick, EconomicsContext ctx) { ... }
    void checkProbes(GameStateTick tick, EconomicsContext ctx) { ... }
    void checkGas(GameStateTick tick, EconomicsContext ctx)    { ... }
    void checkExpansion(GameStateTick tick, EconomicsContext ctx) { ... }
    EconomicsContext updatePhase(GameStateTick tick, EconomicsContext ctx) { ... }
}
```

`BasicEconomicsTask.pylonPosition()` is retained as a package-private static
utility and reused here.

---

## Lifecycle

`AgentOrchestrator.startGame()` / `stopGame()` fire CDI events:

```java
void startGame() {
    engine.connect();
    engine.joinGame();
    cdiEvent.fire(new GameStarted());
}

void stopGame() {
    engine.leaveGame();
    cdiEvent.fire(new GameStopped());
}
```

`EconomicsLifecycle` observes these and manages the flow instance:

```java
@ApplicationScoped
class EconomicsLifecycle {
    @Inject EconomicsFlow flow;
    WorkflowInstance instance;

    void onGameStart(@Observes GameStarted e) {
        instance = flow.instance(Map.of(
            "phase", "SATURATION", "probeTarget", 22,
            "nexusCount", 1, "gas1Built", false, "gas2Built", false
        )).start();
    }

    void onGameStop(@Observes GameStopped e) {
        if (instance != null) instance.cancel();
    }
}
```

---

## Testing

### Unit tests — `EconomicsDecisionServiceTest` (plain JUnit)
- Direct instantiation: `new EconomicsDecisionService(intentQueue)`
- One test per decision function: supplyCheck, probeCheck, gasCheck, expansionCheck, updatePhase
- Drain `IntentQueue` in `@BeforeEach`

### Flow integration tests — `EconomicsFlowIT` (`@QuarkusTest`)
- Start flow instance with synthetic `EconomicsContext`
- Emit `GameStateTick` events; assert `IntentQueue` contents
- Test phase transitions: emit ticks until `saturated=true`, assert phase becomes `EXPANDING`
- `@BeforeEach` / `@AfterEach` drain `IntentQueue` (established pattern)

### Lifecycle test — `EconomicsLifecycleIT` (`@QuarkusTest`)
- Fire `GameStarted`; assert instance running
- Fire `GameStopped`; assert instance cancelled

---

## Drools / Flow Boundary (Forward Compatibility)

Drools CEP (planned — `@role(event)`, temporal operators, sliding windows) will
detect game patterns and write signals to the CaseFile. The flow is designed
from the start to read from signal keys, not raw game state, so that when
CEP lands:

- Drools writes `StarCraftCaseFile.SIGNAL_GAS_READY`, `SIGNAL_SATURATED`, etc.
- `FlowEconomicsTask.execute()` reads those keys into `GameStateTick`
- `EconomicsFlow` and `EconomicsDecisionService` are unchanged

This keeps Drools as the **detection engine** and Flow as the **orchestration
engine**, with CaseHub blackboard as the mediator.

---

## Out of Scope

- Chronoboost (ability cast) — Phase 4+
- Worker gas redirection (move workers into assimilator) — requires unit targeting
- Expansion location scouting — depends on map analysis sidecar (§3.2 library research)
- Second-base Nexus placement — hardcoded position acceptable for now (same as Pylon pattern)
