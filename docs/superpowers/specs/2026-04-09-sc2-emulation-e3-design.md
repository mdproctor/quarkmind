# SC2 Emulation Engine — Phase E3: Combat
**Date:** 2026-04-09
**Supersedes:** `2026-04-09-sc2-emulation-e2-design.md`

---

## Context

E2 gave us movement and a scripted enemy wave. Enemy Zealots march toward the
nexus, probes move on `AttackIntent`, but nothing damages anything. E3 adds
combat: units within attack range deal damage each tick, shields absorb before
HP, and units are removed from the game state when HP reaches zero.

**Approach A** (chosen): flat damage per scheduler tick, two-pass simultaneous
resolution (collect all damage, then apply — no order-dependency). This gives
smooth, visually legible combat at 500ms ticks. Attack cooldowns (B) and damage
types/armour (C) are planned for later phases and slot naturally into the same
architecture.

---

## Scope (Phase E3 only)

**In scope:**
- `Unit` domain record gains `shields` and `maxShields`
- `SC2Data` gains `maxShields(UnitType)`, `damagePerTick(UnitType)`, `attackRange(UnitType)`
- `EmulatedGame.tick()` gains `resolveCombat()` — two-pass simultaneous resolution
- Friendly units with active targets attack nearest enemy in range while closing
- Enemy units always attack nearest friendly in range while advancing
- Unit death: HP ≤ 0 → removed from game state
- Visualizer: sprite tint shifts with HP percentage (full → yellow → red)
- Tests at all three levels: unit (EmulatedGameTest), integration (WebSocket), E2E (Playwright)
- GitHub epic + child issues created before implementation

**Explicitly out of scope:**
- Attack cooldowns / per-attack events (Phase E4)
- Damage types and armour reduction (Phase E5)
- Per-unit HP bars in the visualizer (future)
- Shield regeneration (future)
- Air/ground targeting separation (future)

---

## Phase Roadmap

| Phase | Mechanic | Adds to combat |
|---|---|---|
| **E3** | Flat damage per tick, shields, unit death | Working fights |
| E4 | Attack cooldowns, damage-per-attack | SC2-accurate attack bursts |
| E5 | Damage types, armour, ground/air split | Full SC2 fidelity |

---

## Part 1: Domain Model — `Unit` record

### Change

```java
// Before (E2)
public record Unit(String tag, UnitType type, Point2d position,
                   int health, int maxHealth) {}

// After (E3)
public record Unit(String tag, UnitType type, Point2d position,
                   int health, int maxHealth,
                   int shields, int maxShields) {}
```

### Impact

Every place that constructs a `Unit` must supply `shields` and `maxShields`.
The Java compiler catches every missed site — the refactor is mechanical but
pervasive. Critical: `moveFriendlyUnits()` and `moveEnemyUnits()` in
`EmulatedGame` replace each unit via `new Unit(...)` on every tick — shields
must be carried through or they reset to 0 every tick.

Files requiring update (non-exhaustive — compiler confirms):
- `domain/Unit.java` — record definition
- `sc2/mock/SimulatedGame.java` — `reset()`, `applyIntent(TrainIntent)`, `spawnEnemyUnit()`
- `sc2/emulated/EmulatedGame.java` — `reset()`, `handleTrain()`, `spawnEnemyWaves()`, `moveFriendlyUnits()`, `moveEnemyUnits()`, new `resolveCombat()`
- `sc2/real/ObservationTranslator.java` — creates Units from SC2 observations
- `sc2/mock/ReplaySimulatedGame.java` — creates Units from replay data
- `sc2/mock/scenario/*.java` — scenario helpers that spawn units
- All tests that directly construct `Unit` objects

---

## Part 2: `SC2Data` additions

Three new static methods, pure data, no framework imports:

### `maxShields(UnitType)`

```java
public static int maxShields(UnitType type) {
    return switch (type) {
        case PROBE    -> 20;
        case ZEALOT   -> 50;
        case STALKER  -> 80;
        case IMMORTAL -> 100;
        case OBSERVER -> 20;
        case VOID_RAY -> 100;
        default       -> 0;   // Terran/Zerg have no shields
    };
}
```

### `damagePerTick(UnitType)`

SC2 DPS at Faster speed × 0.5 s per scheduler tick, rounded up to nearest int:

```java
public static int damagePerTick(UnitType type) {
    return switch (type) {
        case PROBE     -> 3;   // 5.8 DPS × 0.5 ≈ 2.9
        case ZEALOT    -> 5;   // 9.7 DPS × 0.5 ≈ 4.9
        case STALKER   -> 5;   // 9.7 DPS × 0.5 ≈ 4.9
        case IMMORTAL  -> 12;  // 23.7 DPS × 0.5 ≈ 11.9
        case MARINE    -> 5;   // 9.8 DPS × 0.5 ≈ 4.9
        case MARAUDER  -> 6;   // 11.0 DPS × 0.5 ≈ 5.5
        case ROACH     -> 7;   // 13.7 DPS × 0.5 ≈ 6.9
        case HYDRALISK -> 9;   // 17.6 DPS × 0.5 ≈ 8.8
        default        -> 4;
    };
}
```

### `attackRange(UnitType)`

Range in tiles. Zealots are melee (0.5 tiles):

```java
public static float attackRange(UnitType type) {
    return switch (type) {
        case ZEALOT    -> 0.5f;
        case PROBE     -> 3.0f;
        case STALKER   -> 5.0f;
        case IMMORTAL  -> 5.5f;
        case MARINE    -> 5.0f;
        case MARAUDER  -> 5.0f;
        case ROACH     -> 4.0f;
        case HYDRALISK -> 5.0f;
        default        -> 3.0f;
    };
}
```

---

## Part 3: `EmulatedGame` — combat resolution

### Updated `tick()` order

```java
public void tick() {
    gameFrame++;
    mineralAccumulator += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
    moveFriendlyUnits();
    moveEnemyUnits();
    resolveCombat();       // new: collect damage, apply, remove dead
    fireCompletions();
    spawnEnemyWaves();
}
```

### `resolveCombat()` — two-pass simultaneous resolution

**Pass 1 — collect damage (no state mutation):**
- For each friendly unit with an active `unitTargets` entry: find nearest enemy within `attackRange`. If found, accumulate `damagePerTick` into `pendingDamage` for that enemy.
- For each enemy unit: find nearest friendly within `attackRange`. If found, accumulate `damagePerTick` into `pendingDamage` for that friendly.

**Pass 2 — apply damage:**
- For each unit tag in `pendingDamage`: subtract from shields first; overflow subtracts from HP.
- Remove any unit whose HP ≤ 0 (also remove its entry from `unitTargets`/`enemyTargets`).

```java
private void resolveCombat() {
    Map<String, Integer> pendingDamage = new HashMap<>();

    // Friendly units attack: only if they have an active move/attack target
    for (Unit attacker : myUnits) {
        if (!unitTargets.containsKey(attacker.tag())) continue;
        nearestInRange(attacker.position(), enemyUnits, SC2Data.attackRange(attacker.type()))
            .ifPresent(target ->
                pendingDamage.merge(target.tag(), SC2Data.damagePerTick(attacker.type()), Integer::sum));
    }

    // Enemy units always attack
    for (Unit attacker : enemyUnits) {
        nearestInRange(attacker.position(), myUnits, SC2Data.attackRange(attacker.type()))
            .ifPresent(target ->
                pendingDamage.merge(target.tag(), SC2Data.damagePerTick(attacker.type()), Integer::sum));
    }

    // Apply damage to friendly units
    myUnits.replaceAll(u -> applyDamage(u, pendingDamage.getOrDefault(u.tag(), 0)));
    myUnits.removeIf(u -> u.health() <= 0);

    // Apply damage to enemy units
    enemyUnits.replaceAll(u -> applyDamage(u, pendingDamage.getOrDefault(u.tag(), 0)));
    enemyUnits.removeIf(u -> {
        if (u.health() <= 0) { enemyTargets.remove(u.tag()); return true; }
        return false;
    });

}

/** Target selection: nearest unit in range, ties broken by lowest HP+shields. */
private static Optional<Unit> nearestInRange(Point2d from, List<Unit> candidates, float range) {
    return candidates.stream()
        .filter(u -> distance(from, u.position()) <= range)
        .min(Comparator.comparingDouble(u ->
            distance(from, u.position()) * 1000 + u.health() + u.shields()));
}

/** Shields absorb damage first; overflow applies to HP. */
private static Unit applyDamage(Unit u, int damage) {
    if (damage <= 0) return u;
    int shieldsLeft = Math.max(0, u.shields() - damage);
    int overflow    = Math.max(0, damage - u.shields());
    int hpLeft      = Math.max(0, u.health() - overflow);
    return new Unit(u.tag(), u.type(), u.position(), hpLeft, u.maxHealth(),
                    shieldsLeft, u.maxShields());
}
```

---

## Part 4: Visualizer — health tinting

In `visualizer.js`, `syncLayer` creates/updates sprites. In `updateScene()`, after
syncing positions, apply a tint to each unit sprite based on its HP percentage:

```javascript
function healthTint(health, maxHealth) {
    if (maxHealth <= 0) return null;
    const ratio = health / maxHealth;
    if (ratio > 0.6) return null;          // full colour — no tint
    if (ratio > 0.3) return 0xffcc44;     // yellow — wounded
    return 0xff3333;                        // red — critical
}
```

Applied in `syncLayer()` after position update:

```javascript
// After setting sprite.x and sprite.y:
const tint = healthTint(entity.health, entity.maxHealth);
s.tint = tint ?? 0xffffff;
```

Dead units (health ≤ 0) are absent from the next `GameState.myUnits` / `enemyUnits` —
`syncLayer`'s existing removal logic handles this automatically.

---

## Part 5: Testing strategy

### Unit tests — `EmulatedGameTest` additions (6 new tests)

| Test | Asserts |
|---|---|
| `shieldsAbsorbDamageBeforeHp` | Apply damage ≤ maxShields: HP unchanged, shields reduced |
| `damageOverflowsFromShieldsToHp` | Apply damage > maxShields: shields → 0, remainder hits HP |
| `unitDiesWhenHpReachesZero` | After enough ticks in combat, unit removed from game state |
| `unitOutsideAttackRangeNotDamaged` | Enemy beyond range: no damage applied |
| `unitInsideAttackRangeReceivesDamage` | Enemy within range: health decreases after tick |
| `combatIsSimultaneous` | Two units kill each other in same tick: both removed (not sequential) |

### Integration tests — `GameStateWebSocketTest` additions (2 new tests)

| Test | Asserts |
|---|---|
| `jsonContainsShieldsAndMaxShields` | GameState JSON has `shields` and `maxShields` fields on Unit objects |
| `healthDecreasesAfterCombatTicks` | Spawn enemy near probes (via SimulatedGame manipulation), tick emulated engine N times, verify health in JSON has decreased |

Note: combat resolution only happens in `EmulatedGame`, not `SimulatedGame`.
For WebSocket integration tests that verify combat, use the `%emulated` profile
or inject and manipulate `EmulatedGame` directly. For the `shields` JSON field
test, any profile works since the domain record carries the field regardless.

### E2E tests — `VisualizerRenderTest` additions (3 new tests)

| Test | Asserts |
|---|---|
| `fullHealthUnitHasNoTint` | Probe at full health: `sprite.tint === 0xffffff` (no tint) |
| `lowHealthUnitHasRedTint` | Inject low-health unit via SimulatedGame, observe → sprite tint !== 0xffffff |
| `unitDisappearsWhenHealthIsZero` | Inject zero-health unit, observe → sprite count decreases |

For the last two: use `SimulatedGame.spawnEnemyUnit()` to add enemies, then
manipulate unit health directly via a new package-private `setHealthForTesting()`
method on `EmulatedGame`. Or: expose a QA endpoint `PUT /qa/emulated/units/{tag}/health`.
The simpler approach is a `setHealthForTesting` helper — the same pattern used
for `setMineralsForTesting` in E2.

---

## Part 6: GitHub issue structure

Create before implementation begins (issue-workflow skill):

| Type | Title |
|---|---|
| Epic | E3: Combat — units can fight and die in EmulatedGame |
| Issue | #E3-1: Add shields/maxShields to Unit domain record (breaking change) |
| Issue | #E3-2: Add SC2Data combat constants (maxShields, damagePerTick, attackRange) |
| Issue | #E3-3: Implement resolveCombat() in EmulatedGame — two-pass simultaneous resolution |
| Issue | #E3-4: Visualizer health tinting — sprite tint shifts with HP% |
| Issue | #E3-5: Unit tests — EmulatedGameTest combat coverage (6 tests) |
| Issue | #E3-6: Integration tests — WebSocket shields JSON + combat health decrease |
| Issue | #E3-7: E2E tests — VisualizerRenderTest tint + unit disappear |

---

## Context Links

- E1 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e1-design.md`
- E2 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md`
- Visualizer spec: `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md`
- GitHub: mdproctor/quarkmind
