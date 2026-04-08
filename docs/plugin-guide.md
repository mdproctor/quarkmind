# Plugin Developer Guide

This guide covers everything needed to write, test, and deploy a new plugin for the QuarkusMind StarCraft II agent.

---

## What a Plugin Is

A plugin is a CDI bean that implements one of four task seam interfaces:

| Seam | Interface | Concern |
|---|---|---|
| `StrategyTask` | `agent.plugin.StrategyTask` | High-level game plan — what to build, when to attack |
| `EconomicsTask` | `agent.plugin.EconomicsTask` | Resource management — workers, supply, gas |
| `TacticsTask` | `agent.plugin.TacticsTask` | Army execution — how and where to fight |
| `ScoutingTask` | `agent.plugin.ScoutingTask` | Information gathering — where is the enemy |

Each interface extends CaseHub's `TaskDefinition`. The platform finds and calls your plugin automatically via CDI — no registration or wiring needed.

---

## Anatomy of a Plugin

```java
@ApplicationScoped          // CDI singleton
@CaseType("starcraft-game") // CaseHub routing key — must be exactly this
public class MyStrategyTask implements StrategyTask {

    @Inject IntentQueue intentQueue;  // inject to queue game commands

    @Override
    public String getId()   { return "strategy.mine"; }    // unique ID
    @Override
    public String getName() { return "My Strategy"; }      // human-readable

    // When this task fires — READY is set by GameStateTranslator each tick
    @Override
    public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }

    // CaseFile keys this task writes (empty if you only queue intents)
    @Override
    public Set<String> producedKeys() { return Set.of(QuarkMindCaseFile.STRATEGY); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        // 1. Read game state from the CaseFile
        int minerals = caseFile.get(QuarkMindCaseFile.MINERALS, Integer.class).orElse(0);
        List<Unit> workers = (List<Unit>) caseFile.get(QuarkMindCaseFile.WORKERS, List.class)
            .orElse(List.of());

        // 2. Make decisions and queue intents
        if (minerals >= 150 && hasGateway(caseFile)) {
            intentQueue.add(new TrainIntent(nexusTag, UnitType.PROBE));
        }

        // 3. Optionally write reasoning state back to the CaseFile
        caseFile.put(QuarkMindCaseFile.STRATEGY, "MACRO");
    }
}
```

---

## Reading Game State

Game state is available in the `CaseFile` via typed `get()` calls. All keys are defined in `QuarkMindCaseFile`.

### Resource keys

| Key constant | Type | Description |
|---|---|---|
| `MINERALS` | `Integer` | Current mineral count |
| `VESPENE` | `Integer` | Current vespene (gas) count |
| `SUPPLY_USED` | `Integer` | Supply currently consumed |
| `SUPPLY_CAP` | `Integer` | Current supply cap |
| `GAME_FRAME` | `Long` | Current game frame (÷22.4 = seconds) |

### Unit and building keys

| Key constant | Type | Description |
|---|---|---|
| `WORKERS` | `List<Unit>` | All Probes |
| `ARMY` | `List<Unit>` | All non-Probe units |
| `MY_BUILDINGS` | `List<Building>` | All player buildings (complete and under construction) |
| `ENEMY_UNITS` | `List<Unit>` | Visible enemy units |

### Agent reasoning keys (written by plugins)

| Key constant | Type | Written by |
|---|---|---|
| `STRATEGY` | `String` | `StrategyTask` — e.g. `"MACRO"`, `"ATTACK"`, `"DEFEND"` |
| `CRISIS` | `String` | Any plugin — signals an emergency condition |

### Reading a typed list

```java
@SuppressWarnings("unchecked")
List<Unit> workers = (List<Unit>) caseFile.get(QuarkMindCaseFile.WORKERS, List.class)
    .orElse(List.of());
```

The `@SuppressWarnings` is required because of Java type erasure on `List.class`. The cast is safe — `GameStateTranslator` always puts the right type.

---

## Queuing Intents

Intents are the only way to command the game. Inject `IntentQueue` and call `add()`:

```java
@Inject IntentQueue intentQueue;

// Train a unit from a building
intentQueue.add(new TrainIntent(nexusTag, UnitType.PROBE));
intentQueue.add(new TrainIntent(gatewayTag, UnitType.ZEALOT));

// Build a structure
intentQueue.add(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(15, 15)));
intentQueue.add(new BuildIntent(probeTag, BuildingType.GATEWAY, new Point2d(17, 18)));

// Move a unit
intentQueue.add(new MoveIntent(unitTag, new Point2d(50, 50)));

// Attack with a unit
intentQueue.add(new AttackIntent(unitTag, new Point2d(100, 100)));
```

**Tags** are unique string identifiers for each unit or building. Read them from `Unit.tag()` or `Building.tag()`.

**Multiple intents per tick are fine.** They are all dispatched at the end of the tick. In mock mode they are applied to `SimulatedGame`; in replay mode they are recorded but not applied; in real SC2 mode they are sent to the game engine.

---

## Replacing a Plugin

To replace the existing `BasicStrategyTask` with your own:

1. Create your class implementing `StrategyTask` with `@ApplicationScoped @CaseType("starcraft-game")`
2. Delete (or deactivate) the existing implementation — two active beans for the same seam will cause CDI ambiguity

If you want both to coexist temporarily, use `@io.quarkus.arc.Priority` to prefer one.

---

## Testing a Plugin

Use `DefaultCaseFile` (from `casehub-core`) to build a test CaseFile without CDI:

```java
class MyStrategyTaskTest {

    IntentQueue intentQueue;
    MyStrategyTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new MyStrategyTask(intentQueue);
    }

    @Test
    void buildsGatewayWhenReady() {
        var cf = new DefaultCaseFile("test", "starcraft-game", null, null);
        cf.put(QuarkMindCaseFile.MINERALS,    200);
        cf.put(QuarkMindCaseFile.WORKERS,     List.of(probe("p-0")));
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus(), completePylon()));
        cf.put(QuarkMindCaseFile.READY,       Boolean.TRUE);

        task.execute(cf);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY);
    }
}
```

See `BasicStrategyTaskTest` and `BasicEconomicsTaskTest` for full examples.

**Never use `@QuarkusTest` for unit tests that don't need CDI** — the boot cost is significant. Use `@QuarkusTest` only when you need the full CDI context (e.g., testing REST endpoints or the full pipeline).

---

## Domain Model Reference

### UnitType

```
PROBE, ZEALOT, STALKER, IMMORTAL, COLOSSUS, CARRIER,
DARK_TEMPLAR, HIGH_TEMPLAR, ARCHON, OBSERVER, VOID_RAY,
UNKNOWN
```

### BuildingType

```
NEXUS, PYLON, GATEWAY, CYBERNETICS_CORE, ASSIMILATOR,
ROBOTICS_FACILITY, STARGATE, FORGE, TWILIGHT_COUNCIL,
UNKNOWN
```

### Unit record

```java
record Unit(String tag, UnitType type, Point2d position, int health, int maxHealth) {}
```

### Building record

```java
record Building(String tag, BuildingType type, Point2d position,
                int health, int maxHealth, boolean isComplete) {}
```

`isComplete` is `false` while a building is under construction. Most plugins should check this before using a building (e.g., `b.isComplete()` before training from a Gateway).

---

## Execution Order

Plugins all run in the same CaseEngine cycle triggered by `AgentOrchestrator.gameTick()`. The order within a cycle is not guaranteed — do not assume one plugin's writes are visible to another in the same tick.

Intents queued by multiple plugins in the same tick are all dispatched together at the end of the tick.

---

## Resource Double-Spend

Two plugins can both queue intents that spend the same minerals in one tick (e.g., `BasicEconomicsTask` queues a Pylon and `BasicStrategyTask` queues a Gateway in the same tick when minerals are just enough for one). 

In mock mode, `SimulatedGame` does not enforce mineral costs — both intents are applied. In real SC2 mode, the game engine will reject commands it cannot honour. Arbitration between plugins is a future concern (see `docs/roadmap-sc2-engine.md`).

For now, budget conservatively: check `minerals >= cost` and leave headroom.
