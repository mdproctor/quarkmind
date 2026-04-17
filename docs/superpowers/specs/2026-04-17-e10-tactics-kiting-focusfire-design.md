# E10 Design: Tactics — Kiting, Focus-Fire, Range Fix

**Date:** 2026-04-17  
**Epic:** E10 — TacticsTask extension  
**Approach:** Approach A — full Drools → GOAP → dispatch pipeline  

---

## Overview

Three coordinated improvements to `DroolsTacticsTask`:

1. **Range fix** — replace hardcoded `STALKER_RANGE = 6.0` with `SC2Data.attackRange(unit.type())` per unit type
2. **Focus-fire** — all attacking units target the same (lowest-HP) enemy rather than independently picking the nearest
3. **Kiting** — ranged units on weapon cooldown step backward to avoid melee contact, re-engaging when cooldown expires

All three features extend the existing Drools → GOAP → Java dispatch pipeline consistently. Kiting exposes `weaponCooldownTicks` on the `Unit` record, matching the SC2 protobuf `weapon_cooldown` field (field 35) that real bot frameworks read.

**Timing caveat:** `SC2Data.attackCooldownInTicks(STALKER) = 3` (1.5 s at 500 ms/tick) is an approximation; real SC2 Stalker period at Faster speed is ~0.535 s (≈ 1 tick). Changing to 1 tick is a one-line future change; out of scope for E10.

---

## Section 1 — Domain & Snapshot

### `Unit` record

Add one field — **last**, to minimise constructor impact:

```java
public record Unit(String tag, UnitType type, Point2d position,
                   int health, int maxHealth,
                   int shields, int maxShields,
                   int weaponCooldownTicks) {}
```

`weaponCooldownTicks = 0` means ready to fire. Matches SC2 `weapon_cooldown` semantics.

### `EmulatedGame.snapshot()`

Read `unitCooldowns` map when building each `Unit` in the snapshot:

```java
int cd = unitCooldowns.getOrDefault(u.tag(), 0);
new Unit(u.tag(), u.type(), u.position(),
         u.health(), u.maxHealth(), u.shields(), u.maxShields(), cd)
```

### `SimulatedGame` and `ReplaySimulatedGame`

Always pass `0` for `weaponCooldownTicks` — these paths do not model cooldowns. Correct behaviour: mock/replay units are always ready to fire.

### Migration

Every test that constructs a `Unit` directly needs the new field appended as `0`. No logic changes — pure constructor migration. A compile pass will catch all sites.

---

## Section 2 — Drools Classification

### `TacticsRuleUnit` additions

```java
private final DataStore<String> onCooldownTags = DataSource.createStore();
```

`focusTargetTag` does NOT go in `TacticsRuleUnit` — focus-fire is pure Java (no Drools rule reads it). `selectFocusTarget()` runs in `DroolsTacticsTask.execute()` before Drools fires.

### Population (in `DroolsTacticsTask.buildRuleUnit`)

```java
// onCooldownTags: units with weapon cooldown remaining
army.stream()
    .filter(u -> u.weaponCooldownTicks() > 0)
    .map(Unit::tag)
    .forEach(data.getOnCooldownTags()::add);
```

### DRL changes

**Existing "Group: in range" rule** — add `not /onCooldownTags` guard (follows same pattern as health guard):

```
rule "Group: in range"
    salience 200
when
    eval(strategyGoal.equals("ATTACK"))
    $u: /army[ (double) this.health() / this.maxHealth() >= 0.3 ]
    /inRangeTags[ this == $u.tag() ]
    not /onCooldownTags[ this == $u.tag() ]
then
    groupDecisions.add("in-range:ENEMY_ELIMINATED:" + $u.tag());
    activeGroups.add("in-range");
end
```

**New Phase 1 rule — "Group: kiting":**

```
rule "Group: kiting"
    salience 200
when
    eval(strategyGoal.equals("ATTACK"))
    $u: /army[ (double) this.health() / this.maxHealth() >= 0.3 ]
    /inRangeTags[ this == $u.tag() ]
    /onCooldownTags[ this == $u.tag() ]
then
    groupDecisions.add("kiting:KITING:" + $u.tag());
    activeGroups.add("kiting");
end
```

Low-health priority is enforced structurally: both kiting and in-range rules guard `health/maxHealth >= 0.3`. A unit below that threshold is captured by the `"Group: low health"` rule (salience 210) and never matches either rule above. No salience trick needed.

**New Phase 2 rule — "Action: Kite available":**

```
rule "Action: Kite available"
    salience 100
when
    /activeGroups[ this == "kiting" ]
then
    actionDecisions.add("KITE:1");
end
```

---

## Section 3 — GOAP Extension

### New action — `KITE`

```java
"KITE", new GoapAction("KITE",
    Map.of("inRange", true, "onCooldown", true, "enemyVisible", true),
    Map.of("onCooldown", false),   // GOAP abstraction: kiting resolves cooldown state
    1)
```

The effect `onCooldown → false` prevents the planner looping on KITE. Goal for the kiting group is `unitSafe` — the unit is spending the cooldown window safely.

### `buildWorldState` — new `"kiting"` case

```java
case "kiting" -> new WorldState(Map.of(
    "lowHealth",       false,
    "enemyVisible",    true,
    "inRange",         true,
    "onCooldown",      true,
    "unitSafe",        false,
    "enemyEliminated", false))
```

### `goalConditionKey` addition

```java
case "KITING" -> "unitSafe"
```

### Plan result

Planner finds: `KITE` → `unitSafe`. Single-step plan. No chaining needed.

---

## Section 4 — Dispatch, Focus-Fire & Range Fix

### Range fix

```java
// Before (buggy):
if (distance(unit.position(), enemy.position()) <= STALKER_RANGE)

// After:
if (distance(unit.position(), enemy.position()) <= SC2Data.attackRange(unit.type()))
```

Delete `static final double STALKER_RANGE = 6.0`. The `TODO(R&D)` comment is resolved.

### Focus-fire

```java
private Optional<Unit> selectFocusTarget(List<Unit> enemies) {
    return enemies.stream()
        .min(Comparator.comparingInt(e -> e.health() + e.shields()));
}
```

`dispatch("ATTACK")` uses `focusTarget` (passed from `execute`) as the `AttackIntent` target location for all units in the group. `dispatch("MOVE_TO_ENGAGE")` keeps using `NEAREST_THREAT` — it navigates toward the enemy cluster, not a specific unit.

### Kite geometry

```java
private Point2d kiteRetreatTarget(Point2d unitPos, List<Unit> enemies) {
    Unit nearest = enemies.stream()
        .min(Comparator.comparingDouble(e -> distance(unitPos, e.position())))
        .orElseThrow();
    double dx = unitPos.x() - nearest.position().x();
    double dy = unitPos.y() - nearest.position().y();
    double len = Math.sqrt(dx * dx + dy * dy);
    if (len < 0.001) return unitPos;  // degenerate: unit on top of enemy, don't move
    return new Point2d(
        (float)(unitPos.x() + dx / len * KITE_STEP),
        (float)(unitPos.y() + dy / len * KITE_STEP));
}

static final double KITE_STEP = 1.0;  // tiles per kite tick
```

`dispatch("KITE")` emits a `MoveIntent` to `kiteRetreatTarget` per unit. No `AttackIntent` — the unit is not in `attackingUnits` during the kite step. It re-engages next tick when GOAP re-evaluates it as off-cooldown and in-range.

**Kite loop:** `in-range (off cooldown) → AttackIntent → fires → on cooldown → kite (MoveIntent backward) → off cooldown → in-range → AttackIntent → ...`

No changes to `EmulatedGame` — the loop is driven entirely by GOAP re-evaluation each tick.

### Dispatch method signature

```java
private void dispatch(GoapAction action, List<String> unitTags,
                      List<Unit> army, List<Unit> enemies,
                      Point2d threat, List<Building> buildings,
                      Optional<Unit> focusTarget)
```

---

## Section 5 — Test Strategy

### Unit tests — `DroolsTacticsTaskTest` (plain JUnit, no CDI)

| Test | What it verifies |
|---|---|
| `inRangeTags_usesPerUnitRange_stalker` | Stalker at 4.5 tiles is in-range (range=5.0), not at 5.5 |
| `inRangeTags_usesPerUnitRange_zealot` | Zealot at 0.4 tiles is in-range (range=0.5), not at 0.6 |
| `selectFocusTarget_returnsLowestHpEnemy` | Three enemies, picks weakest total HP+shields |
| `selectFocusTarget_combinesHpAndShields` | Shields count toward total (full-HP low-shield beats high-HP no-shield) |
| `kiteRetreatTarget_movesAwayFromEnemy` | Unit (10,10), enemy (10,6) → retreat toward (10,11) |
| `kiteRetreatTarget_normalisedForDiagonal` | Diagonal approach → step length = KITE_STEP (unit vector) |
| `kiteRetreatTarget_degenerate_unitOnEnemy` | Overlapping positions → return unit's own position |

### Drools rule tests — `DroolsTacticsRuleUnitTest` (plain JUnit, fires rules directly)

| Test | What it verifies |
|---|---|
| `kitingGroup_classifiedWhenInRangeAndOnCooldown` | Unit in both sets → `"kiting"` group decision |
| `inRangeGroup_notKiting_whenOffCooldown` | Unit in inRangeTags only → `"in-range"` group |
| `lowHealthPriority_overKiting` | Low-health unit on cooldown → `"low-health"`, not `"kiting"` |
| `focusTargetTag_populatedFromLowestHpEnemy` | Focus target tag present in rule unit after fire |

### Integration tests — `DroolsTacticsTaskIT` (`@QuarkusTest`, full CDI pipeline)

| Test | What it verifies |
|---|---|
| `attackInRange_offCooldown_emitsAttackAtFocusTarget` | Two Stalkers, off cooldown → both `AttackIntent` at weakest enemy |
| `attackInRange_onCooldown_emitsMoveIntentBackward` | Stalker on cooldown → `MoveIntent` away from enemy |
| `attackMixed_oneCooldownOneNot_correctIntentPerUnit` | Mixed cooldown state → correct intent per unit |
| `rangeFixed_perUnitRangeUsed` | Zealot range (0.5) used for melee, Stalker range (5.0) for ranged |
| `focusFire_allUnitsTargetSameEnemy` | Three Stalkers → all `AttackIntent` targets identical |
| `kiting_unitMovesAwayFromEnemy` (happy path) | On-cooldown Stalker: retreat target is farther from enemy than current position |

### EmulatedGame E2E tests — `EmulatedGameTest` (plain JUnit, physics loop)

| Test | What it verifies |
|---|---|
| `kitingReducesArmyDamage` | Stalkers kiting Zealots: army HP after N ticks > standing-still baseline |
| `focusFireEliminatesFirstEnemyFaster` | Focus-fire kills one enemy in fewer ticks than spread-fire |

These tests run the full physics loop with real GOAP decisions and verify measurably better combat outcomes — not just correct intent emission.

---

## Out of Scope for E10

- Changing `attackCooldownInTicks(STALKER)` to match real SC2 timing (1 tick) — one-liner, deferred
- Kiting with terrain avoidance (kite step may move into a wall) — deferred to E11+
- Multi-target focus-fire (split attacking force across two targets) — deferred
- Blink micro (Stalker blink ability) — deferred
