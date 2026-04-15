# SC2 Emulation Engine — Phase E6: Enemy Retreat & Regroup
**Date:** 2026-04-15
**Supersedes:** `2026-04-15-e5-damage-types-armour-design.md` (extends, does not replace)

---

## Context

E5 gave the enemy SC2-accurate combat: armour reduction, attribute bonuses, and the
Immortal's Hardened Shield. Enemy units still fight to the death — once launched, they
march to the Nexus with no awareness of how the battle is going. A realistic opponent
pulls back when badly damaged, regroups with reinforcements, and attacks again.

E6 adds two configurable retreat triggers: per-unit health threshold (a wounded unit
retreats individually) and army-wide depletion threshold (the whole wave retreats when
too many units have died). Retreating units animate their withdrawal — they visibly
move back to the staging area, preserving whatever HP they have, and rejoin the next
attack wave.

---

## Phase Roadmap

| Phase | Mechanic | Adds |
|---|---|---|
| E4 | Enemy active AI, build orders, attack cooldowns | Real opponent |
| E5 | Damage types, armour, Hardened Shield | SC2-accurate combat |
| **E6** | Enemy retreat & regroup | Smarter, persistent opponent |
| E7 | Pathfinding | Terrain-aware movement |

---

## Scope

**In scope:**
- `EnemyAttackConfig` — two new fields: `retreatHealthPercent`, `retreatArmyPercent`
- `defaultProtoss()` — updated defaults: `retreatHealthPercent=30`, `retreatArmyPercent=50`
- `EmulatedGame` — two new fields: `retreatingUnits`, `initialAttackSize`
- `EmulatedGame.tickEnemyRetreat()` — new private method in tick loop
- `EmulatedGame.tickEnemyStrategy()` — record `initialAttackSize` on wave launch
- `EmulatedGame.resolveCombat()` — remove dead units from `retreatingUnits`
- `EmulatedGameTest` — 9 new unit tests

**Explicitly out of scope:**
- Visualizer tinting for retreating units (red moving backward is sufficient)
- Shield regeneration during retreat (E8+)
- Retreat pathfinding (E7)
- `GameState` exposing `retreatingUnits` (not needed by agent yet)

---

## Part 1: `EnemyAttackConfig` — two new fields

```java
public record EnemyAttackConfig(
    int armyThreshold,           // existing: send attack when staged army >= this
    int attackIntervalFrames,    // existing: also send if this many frames passed
    int retreatHealthPercent,    // NEW: 0=disabled; unit retreats when HP+shields < X% of max
    int retreatArmyPercent       // NEW: 0=disabled; wave retreats when < X% of launched units alive
) {}
```

**Convention:** `0` means disabled for both new fields. No nulls, no Optional — a 0
value means "never retreat on this trigger". Existing config files that omit the new
fields deserialise to 0 (Jackson default for missing int fields) and therefore have
no retreat behaviour, maintaining full backward compatibility.

`defaultProtoss()` updated:
```java
new EnemyAttackConfig(3, 200, 30, 50)
// armyThreshold=3, attackIntervalFrames=200,
// retreatHealthPercent=30, retreatArmyPercent=50
```

---

## Part 2: `EmulatedGame` — new fields

Added alongside the existing E4 fields:

```java
// E6: retreat tracking
private final Set<String> retreatingUnits = new HashSet<>();
private int initialAttackSize = 0;
```

`reset()` additions:
```java
retreatingUnits.clear();
initialAttackSize = 0;
```

**Tick loop order** — `tickEnemyRetreat()` inserted after `resolveCombat()`:
```
gameFrame++
mineralAccumulator += ...
moveFriendlyUnits()
moveEnemyUnits()
resolveCombat()
tickEnemyRetreat()     ← new
fireCompletions()
spawnEnemyWaves()
tickEnemyStrategy()
```

Ordering rationale: retreat is evaluated on the post-combat state (damage already
applied, dead units already removed). Movement happens next tick — retreating units
get their new target this tick and start moving on the next `moveEnemyUnits()` call.

---

## Part 3: `EmulatedGame.tickEnemyStrategy()` — record wave size on launch

One line added immediately before `enemyStagingArea.clear()` when a wave launches:

```java
initialAttackSize = enemyStagingArea.size();
```

This snapshot is what army-wide retreat calculations use as the denominator.

---

## Part 4: `EmulatedGame.resolveCombat()` — clean up dead retreating units

Inside the `enemyUnits.removeIf` block where dead enemy units are removed, add:

```java
retreatingUnits.remove(u.tag());
```

alongside the existing `enemyTargets.remove(u.tag())` and `enemyCooldowns.remove(u.tag())`.
Prevents stale tags accumulating in `retreatingUnits` when a retreating unit is killed
before it reaches staging.

---

## Part 5: `STAGING_POS` constant

Extract the hardcoded `new Point2d(26, 26)` that already appears in `tickEnemyStrategy`
as a class constant:

```java
private static final Point2d STAGING_POS = new Point2d(26, 26);
```

Used by both `tickEnemyStrategy` (spawn location) and `tickEnemyRetreat` (retreat target).

---

## Part 6: `EmulatedGame.tickEnemyRetreat()`

New private method:

```java
private void tickEnemyRetreat() {
    if (enemyStrategy == null || initialAttackSize == 0) return;
    EnemyAttackConfig atk = enemyStrategy.attackConfig();

    // 1. Per-unit health threshold
    if (atk.retreatHealthPercent() > 0) {
        for (Unit u : enemyUnits) {
            if (retreatingUnits.contains(u.tag())) continue;
            double totalHp    = u.health() + u.shields();
            double maxTotalHp = SC2Data.maxHealth(u.type()) + SC2Data.maxShields(u.type());
            if (totalHp / maxTotalHp * 100 < atk.retreatHealthPercent()) {
                retreatingUnits.add(u.tag());
                enemyTargets.put(u.tag(), STAGING_POS);
                log.debugf("[EMULATED] Unit %s retreating (hp=%.0f%%)", u.tag(),
                    totalHp / maxTotalHp * 100);
            }
        }
    }

    // 2. Army-wide depletion threshold
    if (atk.retreatArmyPercent() > 0) {
        double survivingFraction = (double) enemyUnits.size() / initialAttackSize * 100;
        if (survivingFraction < atk.retreatArmyPercent()) {
            for (Unit u : enemyUnits) {
                if (retreatingUnits.contains(u.tag())) continue;
                retreatingUnits.add(u.tag());
                enemyTargets.put(u.tag(), STAGING_POS);
            }
            log.infof("[EMULATED] Army retreat triggered: %.0f%% surviving (%d/%d)",
                survivingFraction, enemyUnits.size(), initialAttackSize);
        }
    }

    // 3. Transfer arrived units back to enemyStagingArea
    enemyUnits.removeIf(u -> {
        if (!retreatingUnits.contains(u.tag())) return false;
        if (distance(u.position(), STAGING_POS) >= 0.5) return false;
        retreatingUnits.remove(u.tag());
        enemyTargets.remove(u.tag());
        enemyStagingArea.add(u);  // damaged HP preserved — no healing
        log.debugf("[EMULATED] Unit %s arrived at staging (hp=%d shields=%d)",
            u.tag(), u.health(), u.shields());
        return true;
    });
}
```

**Key behaviours:**
- Per-unit check runs first: individual stragglers break away before the army-wide
  check. Note: `enemyUnits.size()` does not change during `tickEnemyRetreat()` —
  units are only physically removed in step 3 (arrival). The army-wide threshold
  therefore counts retreating-in-flight units as alive, which is correct (they haven't
  left the field yet)
- Army-wide check uses `enemyUnits.size()` (all survivors, including in-flight
  retreaters) as numerator — the true alive count
- Retreating units can still be attacked by friendly units and killed before reaching
  staging (their tags are cleaned up in `resolveCombat`)
- Arriving units land in `enemyStagingArea` with damaged HP — they will rejoin the
  next attack wave, giving the agent a tougher opponent over time

---

## Part 7: Testing

All plain JUnit in `EmulatedGameTest` — no `@QuarkusTest` needed.

### New package-private helper

```java
/** Returns the set of tags currently marked as retreating — for retreat assertions. */
Set<String> retreatingUnitTags() { return Set.copyOf(retreatingUnits); }
```

### Test table

| Test | Setup | Asserts |
|---|---|---|
| `lowHealthUnitRetreats` | Enemy unit spawned, HP+shields set to 5% of max, retreatHealthPercent=30, initialAttackSize=1 | After tick: tag in `retreatingUnitTags()`, target changed |
| `healthyUnitDoesNotRetreat` | Enemy unit at 80% HP, retreatHealthPercent=30 | After tick: `retreatingUnitTags()` empty |
| `armyDepletionTriggersGroupRetreat` | Launch 4 units, kill 3, retreatArmyPercent=50, initialAttackSize=4 | After tick: surviving unit in `retreatingUnitTags()` |
| `retreatingUnitMovesTowardStaging` | Unit marked retreating at (14,14) | After tick: position closer to (26,26) than (14,14) |
| `retreatingUnitTransfersToStagingOnArrival` | Unit placed at (25.6, 26), retreating | After tick: unit in `enemyStagingArea`, gone from `enemyUnits` |
| `retreatedUnitKeepsDamagedHp` | Unit at 40 HP retreats and arrives at staging | Unit in `enemyStagingArea` has `health==40`, not `maxHealth` |
| `disabledThresholdsNeverRetreat` | retreatHealthPercent=0, retreatArmyPercent=0, unit at 1 HP | `retreatingUnitTags()` empty |
| `retreatDoesNotFireBeforeFirstAttack` | No wave launched (initialAttackSize=0) | No crash, no retreat |
| `deadUnitRemovedFromRetreatingSet` | Unit marked retreating, then killed in `resolveCombat` | Tag absent from `retreatingUnitTags()` after tick |

---

## Part 8: GitHub Issue Structure

| Type | Title |
|---|---|
| Epic | E6: Enemy Retreat & Regroup |
| Issue | #E6-1: `EnemyAttackConfig` — `retreatHealthPercent` + `retreatArmyPercent` fields |
| Issue | #E6-2: `EmulatedGame` — `retreatingUnits`, `initialAttackSize`, `STAGING_POS` constant |
| Issue | #E6-3: `EmulatedGame.tickEnemyRetreat()` — per-unit + army-wide thresholds + transfer |
| Issue | #E6-4: Tests — 9 new `EmulatedGameTest` unit tests + `retreatingUnitTags()` helper |

---

## Context Links

- E5 spec: `docs/superpowers/specs/2026-04-15-e5-damage-types-armour-design.md`
- E4 spec: `docs/superpowers/specs/2026-04-14-sc2-emulation-e4-design.md`
- GitHub: mdproctor/quarkmind
