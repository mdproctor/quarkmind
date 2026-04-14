# SC2 Emulation Engine — Phase E4: Enemy Active AI + Attack Cooldowns
**Date:** 2026-04-14
**Supersedes:** `2026-04-09-sc2-emulation-e3-design.md`

---

## Context

E3 gave us working combat: shields absorb before HP, two-pass simultaneous resolution,
unit death, and health tinting in the visualizer. The enemy is still a scripted wave —
it spawns at a fixed frame and marches toward the nexus with no economy or decision-making.

E4 makes the enemy a real opponent:
- The enemy accumulates minerals, follows a configurable build order, and trains units
  into a staging area
- When an army-size threshold or a frame timer fires, the staged army attacks
- Attack cooldowns replace flat per-tick damage, giving SC2-accurate burst attacks
- All enemy behaviour is driven by an `EnemyStrategy` domain object — configurable
  programmatically, via YAML/JSON file at startup, or via REST at runtime

---

## Scope (Phase E4 only)

**In scope:**
- `EnemyBuildStep`, `EnemyAttackConfig`, `EnemyStrategy` domain records — pure Java,
  Jackson-annotated, no framework deps
- `EnemyStrategy.defaultProtoss()` static factory + optional file loading via `EmulatedConfig`
- `EmulatedGame`: enemy mineral accumulation, build order execution, staging area,
  attack trigger (army threshold OR frame timer)
- `GameState`: new `enemyStagingArea` field
- `SC2Data`: `damagePerAttack(UnitType)` + `attackCooldownInTicks(UnitType)`;
  `damagePerTick` removed
- `EmulatedGame.resolveCombat()`: per-unit cooldown maps replace flat per-tick damage
- `attackingUnits` cancel path: `MoveIntent` now removes a unit from `attackingUnits`
- `EmulatedConfigResource`: new `PUT /qa/emulated/config/enemy-strategy` endpoint
- Visualizer: staged units rendered with blue tint in a separate layer
- Tests at all three levels: unit (`EmulatedGameTest`), integration (`@QuarkusTest`),
  E2E (`VisualizerRenderTest`)

**Explicitly out of scope:**
- Enemy retreat / regrouping after a failed attack (E5)
- Damage types and armour (E5)
- Pathfinding (E5)
- Enemy buildings visible in the visualizer (future)
- Shield regeneration (future)

---

## Phase Roadmap

| Phase | Mechanic | Adds |
|---|---|---|
| E3 | Flat damage per tick, shields, unit death | Working fights |
| **E4** | Enemy active AI, build orders, attack cooldowns | Real opponent |
| E5 | Damage types, armour, retreat, pathfinding | Full SC2 fidelity |

---

## Part 1: Domain Model — `EnemyStrategy` records

Three new records in `domain/` — pure Java, no framework imports, Jackson-annotated
for JSON/YAML serde. Constructable programmatically, loadable from file, swappable
via REST. The same Java objects serve all three uses.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnemyBuildStep(UnitType unitType) {}

public record EnemyAttackConfig(
    int armyThreshold,        // send attack when staged army size ≥ this
    int attackIntervalFrames  // also send if this many frames passed since last attack
) {}

public record EnemyStrategy(
    List<EnemyBuildStep> buildOrder,
    boolean loop,             // restart from index 0 after completing build order
    int mineralsPerTick,      // enemy mineral income per tick
    EnemyAttackConfig attackConfig
) {
    public static EnemyStrategy defaultProtoss() {
        return new EnemyStrategy(
            List.of(
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT)
            ),
            true, 2,
            new EnemyAttackConfig(3, 200)
        );
    }
}
```

A sample `enemy-strategy.yaml` lives in `src/main/resources/` as documentation:

```yaml
buildOrder:
  - unitType: ZEALOT
  - unitType: ZEALOT
  - unitType: STALKER
loop: true
mineralsPerTick: 2
attackConfig:
  armyThreshold: 3
  attackIntervalFrames: 200
```

---

## Part 2: `SC2Data` — Attack Cooldowns

`damagePerTick` is **deleted**. All call sites (only `resolveCombat()`) are updated.
Two replacements:

```java
/** Damage dealt per attack event (not per tick). */
public static int damagePerAttack(UnitType type) {
    return switch (type) {
        case PROBE     ->  5;
        case ZEALOT    ->  8;
        case STALKER   -> 13;
        case IMMORTAL  -> 20;
        case MARINE    ->  6;
        case MARAUDER  -> 10;
        case ROACH     ->  9;
        case HYDRALISK -> 12;
        default        ->  5;
    };
}

/** Ticks between attacks (cooldown period after firing). */
public static int attackCooldownInTicks(UnitType type) {
    return switch (type) {
        case MARINE, HYDRALISK                              -> 1;
        case PROBE, ZEALOT, IMMORTAL, MARAUDER, ROACH      -> 2;
        case STALKER                                        -> 3;
        default                                             -> 2;
    };
}
```

---

## Part 3: `EmulatedGame` — Cooldowns, Cancel Path, Enemy Economy

### 3a: New fields

```java
// E4: per-unit attack cooldowns (tag → ticks remaining; absent = 0 = can attack)
private final Map<String, Integer> unitCooldowns  = new HashMap<>();
private final Map<String, Integer> enemyCooldowns = new HashMap<>();

// E4: enemy economy
private EnemyStrategy enemyStrategy;
private double        enemyMineralAccumulator;
private int           enemyBuildIndex;
private long          framesSinceLastAttack;
private final List<Unit> enemyStagingArea = new ArrayList<>();
```

### 3b: `reset()` additions

```java
unitCooldowns.clear();
enemyCooldowns.clear();
enemyMineralAccumulator = 0;
enemyBuildIndex         = 0;
framesSinceLastAttack   = 0;
enemyStagingArea.clear();
```

### 3c: `attackingUnits` cancel path

`MoveIntent` now removes a unit from `attackingUnits`, stopping auto-attack:

```java
private void setTarget(String tag, Point2d target, boolean isAttack) {
    if (myUnits.stream().anyMatch(u -> u.tag().equals(tag))) {
        unitTargets.put(tag, target);
        if (isAttack) attackingUnits.add(tag);
        else          attackingUnits.remove(tag);
    }
}
```

### 3d: `resolveCombat()` — per-tick order with cooldowns

```java
private void resolveCombat() {
    // Step 1: decrement all cooldowns (floor 0)
    unitCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));
    enemyCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));

    Map<String, Integer> pending = new HashMap<>();
    Set<String> firedFriendly = new HashSet<>();
    Set<String> firedEnemy    = new HashSet<>();

    // Step 2: collect damage from units with cooldown == 0
    for (Unit attacker : myUnits) {
        if (!attackingUnits.contains(attacker.tag())) continue;
        if (unitCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
        nearestInRange(attacker.position(), enemyUnits, SC2Data.attackRange(attacker.type()))
            .ifPresent(target -> {
                pending.merge(target.tag(), SC2Data.damagePerAttack(attacker.type()), Integer::sum);
                firedFriendly.add(attacker.tag());
            });
    }
    for (Unit attacker : enemyUnits) {
        if (enemyCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
        nearestInRange(attacker.position(), myUnits, SC2Data.attackRange(attacker.type()))
            .ifPresent(target -> {
                pending.merge(target.tag(), SC2Data.damagePerAttack(attacker.type()), Integer::sum);
                firedEnemy.add(attacker.tag());
            });
    }

    // Step 3: apply damage (two-pass — no order dependency)
    myUnits.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
    myUnits.removeIf(u -> {
        if (u.health() <= 0) {
            unitTargets.remove(u.tag());
            attackingUnits.remove(u.tag());
            unitCooldowns.remove(u.tag());
            return true;
        }
        return false;
    });
    enemyUnits.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
    enemyUnits.removeIf(u -> {
        if (u.health() <= 0) {
            enemyTargets.remove(u.tag());
            enemyCooldowns.remove(u.tag());
            return true;
        }
        return false;
    });

    // Step 4: reset cooldown for units that fired
    firedFriendly.forEach(tag -> myUnits.stream()
        .filter(u -> u.tag().equals(tag)).findFirst()
        .ifPresent(u -> unitCooldowns.put(tag, SC2Data.attackCooldownInTicks(u.type()))));
    firedEnemy.forEach(tag -> enemyUnits.stream()
        .filter(u -> u.tag().equals(tag)).findFirst()
        .ifPresent(u -> enemyCooldowns.put(tag, SC2Data.attackCooldownInTicks(u.type()))));
}
```

### 3e: `tickEnemyStrategy()`

Called from `tick()` after `spawnEnemyWaves()`:

```java
private void tickEnemyStrategy() {
    if (enemyStrategy == null) return;

    // Accumulate enemy minerals
    enemyMineralAccumulator += enemyStrategy.mineralsPerTick();

    // Execute next build step if affordable and order not exhausted
    List<EnemyBuildStep> order = enemyStrategy.buildOrder();
    if (!order.isEmpty()) {
        boolean canAdvance = enemyStrategy.loop() || enemyBuildIndex < order.size();
        if (canAdvance) {
            EnemyBuildStep step = order.get(enemyBuildIndex % order.size());
            int cost = SC2Data.mineralCost(step.unitType());
            if ((int) enemyMineralAccumulator >= cost) {
                enemyMineralAccumulator -= cost;
                String tag = "enemy-" + nextTag++;
                int hp = SC2Data.maxHealth(step.unitType());
                enemyStagingArea.add(new Unit(tag, step.unitType(),
                    new Point2d(26, 26), hp, hp,
                    SC2Data.maxShields(step.unitType()), SC2Data.maxShields(step.unitType())));
                if (enemyStrategy.loop() || enemyBuildIndex < order.size() - 1)
                    enemyBuildIndex++;
                else
                    enemyBuildIndex = order.size(); // mark exhausted
            }
        }
    }

    // Check attack triggers
    framesSinceLastAttack++;
    EnemyAttackConfig atk = enemyStrategy.attackConfig();
    boolean thresholdMet = enemyStagingArea.size() >= atk.armyThreshold();
    boolean timerFired   = framesSinceLastAttack >= atk.attackIntervalFrames();
    if ((thresholdMet || timerFired) && !enemyStagingArea.isEmpty()) {
        for (Unit u : enemyStagingArea) {
            enemyUnits.add(u);
            enemyTargets.put(u.tag(), NEXUS_POS);
        }
        enemyStagingArea.clear();
        framesSinceLastAttack = 0;
        log.infof("[EMULATED] Enemy attack launched: %d units", enemyUnits.size());
    }
}
```

### 3f: `snapshot()` — expose staging area

```java
public GameState snapshot() {
    return new GameState(
        (int) mineralAccumulator, vespene, supply, supplyUsed,
        List.copyOf(myUnits), List.copyOf(myBuildings),
        List.copyOf(enemyUnits), List.copyOf(enemyStagingArea),
        List.copyOf(geysers), gameFrame);
}
```

`GameState` gains `List<Unit> enemyStagingArea()`. `GameStateTranslator`, `ObservationTranslator`,
`MockGameObserver`, and `SimulatedGame` return `List.of()` for this field (no staging concept
in those paths). Compiler finds every construction site.

### 3g: Package-private setters (for `EmulatedEngine` and tests)

```java
void setEnemyStrategy(EnemyStrategy s) { this.enemyStrategy = s; }
int  enemyStagingSize()                { return enemyStagingArea.size(); }  // test helper
int  enemyMinerals()                   { return (int) enemyMineralAccumulator; } // test helper
```

---

## Part 4: `EmulatedConfig` Integration

### `EmulatedConfig`

```java
@ConfigProperty(name = "emulated.enemy.strategy-file", defaultValue = "")
String strategyFile;

private volatile EnemyStrategy enemyStrategy = EnemyStrategy.defaultProtoss();

@PostConstruct
void init() {
    // existing init ...
    if (!strategyFile.isBlank()) {
        try {
            enemyStrategy = objectMapper.readValue(
                Path.of(strategyFile).toFile(), EnemyStrategy.class);
            log.infof("[CONFIG] Loaded enemy strategy from %s", strategyFile);
        } catch (Exception e) {
            log.warnf("[CONFIG] Could not load strategy file %s — using default", strategyFile);
        }
    }
}

public EnemyStrategy getEnemyStrategy() { return enemyStrategy; }
public void setEnemyStrategy(EnemyStrategy s) { this.enemyStrategy = s; }
```

Jackson + `jackson-dataformat-yaml` detects extension (`.json` or `.yaml`) automatically.

### `EmulatedConfigResource`

New endpoint alongside existing `GET/PUT /qa/emulated/config`:

```java
@PUT
@Path("/enemy-strategy")
@Consumes({MediaType.APPLICATION_JSON, "application/yaml"})
public Response setEnemyStrategy(EnemyStrategy strategy) {
    emulatedConfig.setEnemyStrategy(strategy);
    emulatedGame.setEnemyStrategy(strategy);
    return Response.ok().build();
}

@GET
@Path("/enemy-strategy")
@Produces(MediaType.APPLICATION_JSON)
public EnemyStrategy getEnemyStrategy() {
    return emulatedConfig.getEnemyStrategy();
}
```

### `EmulatedEngine`

Passes strategy to `EmulatedGame` each tick, same pattern as `unitSpeed`:

```java
game.setEnemyStrategy(emulatedConfig.getEnemyStrategy());
```

---

## Part 5: Visualizer

`visualizer.js` adds a staging layer. Staged units render with a blue tint (`0x4488ff`)
to distinguish "waiting to attack" from active `enemyUnits`. Dead staged units vanish
automatically via the existing removal logic in `syncLayer`.

```javascript
syncLayer(state.enemyStagingArea ?? [], stagingLayer, ENEMY_TEXTURES, 0x4488ff);
```

No other JS changes needed — `syncLayer` is already parameterised on tint.

---

## Part 6: Testing Strategy

### Unit tests — `EmulatedGameTest` (~15 new, ~2 updated)

**Attack cooldowns:**

| Test | Asserts |
|---|---|
| `unitDoesNotAttackWhenCooldownNonZero` | Unit in range, cooldown > 0: no damage dealt |
| `unitAttacksWhenCooldownIsZero` | Unit in range, cooldown = 0: deals `damagePerAttack` |
| `cooldownResetsAfterAttack` | After firing: cooldown = `attackCooldownInTicks`; next tick no damage |
| `cooldownDecrementsEachTick` | After N ticks cooldown reaches 0; attack fires on tick N+1 |
| `moveIntentCancelsAutoAttack` | AttackIntent then MoveIntent: unit stops dealing damage |

**Enemy economy:**

| Test | Asserts |
|---|---|
| `enemyAccumulatesMineralsEachTick` | After enough ticks, staging area gains a unit |
| `enemyTrainsUnitWhenMineralsAfford` | Ticks until minerals ≥ Zealot cost → staging has 1 unit |
| `enemyDoesNotTrainWhenInsufficientMinerals` | `mineralsPerTick=0`: staging stays empty |
| `enemySendsAttackWhenArmyThresholdMet` | staging ≥ threshold → units move to `enemyUnits`, staging clears |
| `enemySendsAttackWhenTimerFires` | Timer fires with non-empty staging → attack sent, timer resets |
| `bothTriggerConditionsCheckedIndependently` | threshold=10, timer=5 frames: timer fires first |
| `enemyStagingClearedAfterAttack` | After attack: `enemyStagingArea` is empty |
| `enemyBuildOrderLoops` | `loop=true`: after 3-step order, index resets; 4th unit trains |
| `enemyBuildOrderStopsWhenExhausted` | `loop=false`: stops after last step |
| `enemyUnitsStayAtSpawnUntilAttack` | Staged units remain at (26,26) until attack fires |
| `enemyStrategyNullIsNoop` | `setEnemyStrategy(null)`: no crash, no staging |

**Updated E3 tests:**

| Test | Change |
|---|---|
| `damageOverflowsFromShieldsToHp` | Expected HP: `maxHealth - (damagePerAttack(ZEALOT) - 3) = 40` |
| Any other exact-damage assertions | Adjust to `damagePerAttack` values |

### Integration tests — `@QuarkusTest` (~5 new)

| Test | Asserts |
|---|---|
| `defaultStrategyActiveOnStartup` | After N ticks in `%emulated`, `enemyStagingArea` non-empty |
| `enemyStrategySwappableViaRest` | `PUT /qa/emulated/config/enemy-strategy` → new order takes effect |
| `gameStateJsonContainsEnemyStagingArea` | WebSocket JSON has `enemyStagingArea` array |
| `attackCooldownLimitsDamageRate` | Damage drops on cooldown ticks, spikes on attack ticks |
| `enemyAttackWaveReachesBase` | Enough ticks → staging units clear, appear in `enemyUnits` with nexus target |

### E2E tests — `VisualizerRenderTest` (~2 new)

| Test | Asserts |
|---|---|
| `enemyStagedUnitsRenderAtSpawn` | Blue-tinted sprites appear at (26,26) before attack threshold |
| `stagedUnitsTransferOnAttack` | After threshold ticks: staging-layer sprites move toward nexus |

---

## Part 7: GitHub Issue Structure

Create before implementation (issue-workflow):

| Type | Title |
|---|---|
| Epic | E4: Enemy Active AI + Attack Cooldowns |
| Issue | #E4-1: `EnemyBuildStep`, `EnemyAttackConfig`, `EnemyStrategy` domain records + Jackson serde |
| Issue | #E4-2: `SC2Data` — replace `damagePerTick` with `damagePerAttack` + `attackCooldownInTicks` |
| Issue | #E4-3: `EmulatedGame` — per-unit cooldown maps + cancel path fix |
| Issue | #E4-4: `GameState` — add `enemyStagingArea` field (breaking change) |
| Issue | #E4-5: `EmulatedGame.tickEnemyStrategy()` — enemy economy, build order, attack triggers |
| Issue | #E4-6: `EmulatedConfig` + `EmulatedConfigResource` — strategy loading + REST endpoint |
| Issue | #E4-7: Visualizer — staging layer with blue tint |
| Issue | #E4-8: Unit tests — `EmulatedGameTest` cooldown + economy coverage (~15 new) |
| Issue | #E4-9: Integration tests — strategy REST + WebSocket staging area (~5 new) |
| Issue | #E4-10: E2E tests — `VisualizerRenderTest` staging render + attack transfer (~2 new) |

---

## Context Links

- E1 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e1-design.md`
- E2 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md`
- E3 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e3-design.md`
- Visualizer spec: `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md`
- GitHub: mdproctor/quarkmind
