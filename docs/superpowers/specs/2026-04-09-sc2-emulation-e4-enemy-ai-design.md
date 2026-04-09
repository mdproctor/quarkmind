# SC2 Emulation Engine — Phase E4: Enemy Active AI
**Date:** 2026-04-09
**Supersedes:** `2026-04-09-sc2-emulation-e3-design.md`

---

## Context

E3 gave us working combat: units deal damage, shields absorb, units die. The enemy
is still scripted — a single wave spawned at a fixed frame via `configureWave()`.
E4 replaces the scripted wave with an active enemy that mines minerals, builds
production infrastructure, trains combat units, and decides when to attack.

The enemy is fully visible in `GameState` (buildings + workers), giving the
player bot's scouting and tactics something real to observe and react to.

---

## Scope (Phase E4 only)

**In scope:**
- `EnemyAI` class — enemy brain with mineral accumulation, build order, and attack decision
- `AttackStrategy` sealed interface — three variants: Threshold, Timer, Hybrid
- `EnemyRace` enum — Terran initially; parameterises worker/building/unit types
- `GameState` gains `List<Building> enemyBuildings`
- `UnitType` gains `SCV`
- `BuildingType` gains `COMMAND_CENTER`, `SUPPLY_DEPOT`, `BARRACKS`
- `SC2Data` gains cost/stat/time data for new types
- `EmulatedGame.configureEnemyAI(EnemyRace, AttackStrategy)` replaces `configureWave()`
- `MoveIntent` cancel path — clears tag from `attackingUnits` so units stop auto-attacking on retreat
- Tests: 10 unit tests + 1 integration (WebSocket snapshot), Playwright deferred

**Explicitly out of scope:**
- Enemy gas harvesting / Assimilator / Refinery (Marines only — zero gas cost)
- Enemy Marauders (deferred until gas support added — one-liner when ready)
- Visualiser sprites for enemy buildings (deferred to visualiser pass)
- Additional enemy races (Zerg, Protoss mirror) — EnemyRace enum is the extension point
- Attack cooldowns / damage types (Phase E5)

---

## Phase Roadmap

| Phase | Mechanic |
|---|---|
| E3 | Flat damage, shields, unit death |
| **E4** | Enemy active AI: economy, production, attack strategy |
| E5 | Attack cooldowns, damage types, armour |
| E6 | Pathfinding + terrain |

---

## Part 1: New Components

### `AttackStrategy` (sealed interface, `sc2/emulated/`)

```java
public sealed interface AttackStrategy
    permits AttackStrategy.Threshold, AttackStrategy.Timer, AttackStrategy.Hybrid {

    record Threshold(int minArmy) implements AttackStrategy {}
    record Timer(int everyNTicks) implements AttackStrategy {}
    record Hybrid(int minArmy, int everyNTicks) implements AttackStrategy {}
}
```

Attack fires when:
- `Threshold`: `armySize >= minArmy`
- `Timer`: `gameFrame % everyNTicks == 0`
- `Hybrid`: both conditions true

### `EnemyRace` (enum, `sc2/emulated/`)

```java
public enum EnemyRace {
    TERRAN(UnitType.SCV, BuildingType.COMMAND_CENTER,
           BuildingType.SUPPLY_DEPOT, BuildingType.BARRACKS,
           List.of(UnitType.MARINE));
    // Future: ZERG, PROTOSS

    public final UnitType workerType;
    public final BuildingType mainBuilding;
    public final BuildingType supplyBuilding;
    public final BuildingType productionBuilding;
    public final List<UnitType> combatUnits;
}
```

### `EnemyAI` (class, `sc2/emulated/`)

Not a CDI bean — owned by `EmulatedGame`. Holds:

```java
class EnemyAI {
    private final EnemyRace race;
    private final AttackStrategy strategy;
    private double mineralAccumulator;
    private int enemySupply;
    private int enemySupplyUsed;
    private boolean buildingSupply;   // guard: don't queue two depots at once
    private boolean buildingBarracks; // guard: don't queue two barracks at once

    // References to EmulatedGame's shared lists (populated during tick)
    // enemyUnits and enemyBuildings are passed in — EnemyAI writes to them
}
```

`EnemyAI` receives a `Supplier<String>` tag generator at construction (lambda from
`EmulatedGame` that increments its shared `nextTag` counter). `tick(long gameFrame)`
is called by `EmulatedGame.tick()` each frame. Executes build order then attack decision.

**Build order (priority, first match wins):**
1. Supply blocked (supplyUsed ≥ supply − 2) and not already building depot and minerals ≥ 100 → queue Supply Depot
2. No Barracks complete and not already building one and minerals ≥ 150 → queue Barracks
3. Worker count < 8 and minerals ≥ 50 and Command Center complete → queue SCV
4. Barracks complete and minerals ≥ 50 → queue Marine

**Attack decision (end of tick):**
```java
boolean shouldAttack = switch (strategy) {
    case Threshold(int min)         -> armySize >= min;
    case Timer(int every)           -> gameFrame % every == 0 && gameFrame > 0;
    case Hybrid(int min, int every) -> armySize >= min && gameFrame % every == 0;
};
```
When attack fires: all combat units get `enemyTargets` set to `NEXUS_POS`.

**Initial state (Terran):**
- 1 Command Center (complete), 6 SCVs, 50 minerals, supply 11/6
- (Command Center gives 11 supply; 6 SCVs × 1 = 6 supply used)

---

## Part 2: Domain Model Changes

### `UnitType`
```java
// Add to Terran section:
SCV,
```

### `BuildingType`
```java
// Add to Terran section:
COMMAND_CENTER, SUPPLY_DEPOT, BARRACKS,
```

### `GameState`
```java
// Before (E3)
public record GameState(int minerals, int vespene, int supply, int supplyUsed,
    List<Unit> myUnits, List<Building> myBuildings,
    List<Unit> enemyUnits, List<Resource> geysers, long gameFrame)

// After (E4)
public record GameState(int minerals, int vespene, int supply, int supplyUsed,
    List<Unit> myUnits, List<Building> myBuildings,
    List<Unit> enemyUnits, List<Building> enemyBuildings,
    List<Resource> geysers, long gameFrame)
```

`EmulatedGame.snapshot()` populates `enemyBuildings` from the enemy buildings list.
All other `GameState` constructors (test helpers, `ObservationTranslator`,
`GameStateTranslator`) gain `List.of()` as the default for `enemyBuildings` until
real SC2 observation wires it up.

### `SC2Data` additions

| Query | COMMAND_CENTER | SUPPLY_DEPOT | BARRACKS | SCV |
|---|---|---|---|---|
| `mineralCost` | 400 | 100 | 150 | 50 |
| `buildTimeInTicks` | 60 | 18 | 47 | — |
| `trainTimeInTicks` | — | — | — | 12 |
| `maxBuildingHealth` | 1500 | 400 | 1000 | — |
| `supplyBonus` | 11 | 8 | 0 | — |
| `maxHealth(SCV)` | — | — | — | 45 |
| `maxShields(SCV)` | — | — | — | 0 |
| `supplyCost(SCV)` | — | — | — | 1 |
| `damagePerTick(SCV)` | — | — | — | 3 |
| `attackRange(SCV)` | — | — | — | 0.5f |

---

## Part 3: `EmulatedGame` Changes

### `configureEnemyAI()` replaces `configureWave()`

```java
// Old
void configureWave(long spawnFrame, int unitCount, UnitType unitType)

// New
void configureEnemyAI(EnemyRace race, AttackStrategy strategy)
```

`configureWave()` is removed. The `EnemyWave` record and `pendingWaves` list are
removed. `EnemyAI` is instantiated in `configureEnemyAI()` and reset in `reset()`.

### `tick()` order

EmulatedGame gains `private final List<Building> enemyBuildings = new ArrayList<>()`,
cleared in `reset()`, and passed into `snapshot()`.

```java
public void tick() {
    gameFrame++;
    mineralAccumulator += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
    enemyAI.tick(gameFrame);
    moveFriendlyUnits();
    moveEnemyUnits();
    resolveCombat();
    fireCompletions();
}
```

`spawnEnemyWaves()` is removed (replaced by `enemyAI.tick()`).

### `attackingUnits` cancel path

```java
private void setTarget(String tag, Point2d target, boolean isAttack) {
    if (myUnits.stream().anyMatch(u -> u.tag().equals(tag))) {
        unitTargets.put(tag, target);
        if (isAttack) attackingUnits.add(tag);
        else attackingUnits.remove(tag);   // MoveIntent cancels attack mode
    }
}
```

---

## Part 4: Testing

All tests in `EmulatedGameTest` (plain JUnit, no CDI).

### New unit tests

| Test | Assertion |
|---|---|
| `enemyTrainsSCV_whenMineralsAvailable` | After N ticks, enemyUnits contains ≥1 SCV |
| `enemyBuildsSupplyDepot_whenSupplyBlocked` | At supply cap, depot appears in enemyBuildings |
| `enemyBuildsBarracks_afterCommandCenter` | Barracks in enemyBuildings once minerals allow |
| `enemyTrainsMarine_afterBarracks` | Marine in enemyUnits once Barracks complete |
| `enemyUnitsAndBuildingsInGameState` | snapshot().enemyBuildings non-empty; enemyUnits contains SCV |
| `enemyAttacks_thresholdStrategy` | Army ≥ threshold → enemyTargets set to nexus |
| `enemyAttacks_timerStrategy` | At frame K, attack fires regardless of army size |
| `enemyAttacks_hybridStrategy` | Both conditions required; fails if only one met |
| `enemyDoesNotAttack_belowThreshold` | Army < threshold → no attack |
| `moveIntent_clearsAttackingFlag` | Unit given MoveIntent stops auto-attacking |

### Integration test

`enemyBuildingsInWebSocketSnapshot` — `GameState` JSON includes `enemyBuildings`
array (existing `GameStateWebSocketTest` pattern).

### Playwright

Deferred — visualiser does not yet render enemy buildings.
Enemy combat units (Marines) already render as sprites via existing `enemyUnits` path.

---

## File Map

| Action | File | Change |
|---|---|---|
| New | `sc2/emulated/AttackStrategy.java` | Sealed interface + 3 records |
| New | `sc2/emulated/EnemyRace.java` | Enum with Terran configuration |
| New | `sc2/emulated/EnemyAI.java` | Enemy brain |
| Modify | `sc2/emulated/EmulatedGame.java` | Wire EnemyAI, remove EnemyWave/configureWave |
| Delete | `sc2/emulated/EnemyWave.java` | Replaced by EnemyAI |
| Modify | `domain/UnitType.java` | Add SCV |
| Modify | `domain/BuildingType.java` | Add COMMAND_CENTER, SUPPLY_DEPOT, BARRACKS |
| Modify | `domain/GameState.java` | Add enemyBuildings field |
| Modify | `domain/SC2Data.java` | Cost/stat data for new types |
| Modify | `agent/GameStateTranslator.java` | Pass List.of() for enemyBuildings |
| Modify | `sc2/real/ObservationTranslator.java` | Pass List.of() for enemyBuildings |
| Modify | `sc2/emulated/EmulatedGame.java` | snapshot() populates enemyBuildings |
| Modify | All test GameState constructors | Add List.of() for enemyBuildings |
| Modify | `EmulatedGameTest.java` | 10 new tests + update existing wave tests |
| Modify | `GameStateWebSocketTest.java` | Assert enemyBuildings in JSON |

---

## Context Links

- E3 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e3-design.md`
- Design snapshot: `docs/design-snapshots/2026-04-09-emulation-e3-combat-complete.md`
- Post-E3 benchmark: `docs/benchmarks/2026-04-09-post-e3-baseline.md`
- GitHub: mdproctor/quarkmind
