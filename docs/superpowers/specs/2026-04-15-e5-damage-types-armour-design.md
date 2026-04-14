# SC2 Emulation Engine — Phase E5: Damage Types, Armour & Hardened Shield
**Date:** 2026-04-15
**Supersedes:** `2026-04-14-sc2-emulation-e4-design.md` (extends, does not replace)

---

## Context

E4 gave the enemy real economy, build orders, and accurate per-unit attack cooldowns.
Combat resolution still ignores SC2's damage modifiers: every hit does raw damage with
no armour reduction and no bonus damage vs unit attributes. The Immortal's Hardened
Shield passive is unimplemented. Several units (Immortal, Marine, Marauder, Roach,
Hydralisk) have incorrect HP values that default to 100.

E5 corrects all of this in one focused pass: accurate stats, armour, attribute bonuses,
and Hardened Shield — implemented behind a testable `DamageCalculator` class so each
layer can be unit-tested in isolation.

---

## Phase Roadmap

| Phase | Mechanic | Adds |
|---|---|---|
| E3 | Shields, simultaneous two-pass combat | Working fights |
| E4 | Enemy active AI, build orders, attack cooldowns | Real opponent |
| **E5** | Damage types, armour, Hardened Shield, stat corrections | SC2-accurate combat |
| E6 | Enemy retreat / regrouping after failed attack | Smarter opponent |
| E7 | Pathfinding | Terrain-aware movement |

---

## Scope

**In scope:**
- `UnitAttribute` enum in `domain/`
- Four new `SC2Data` methods: `unitAttributes`, `armour`, `bonusDamageVs`, `hasHardenedShield`
- SC2Data HP corrections: Immortal (200), Marine (45), Marauder (125), Roach (145), Hydralisk (90)
- `DamageCalculator` — package-private class in `sc2/emulated/`
- `EmulatedGame.resolveCombat()` — use `DamageCalculator` instead of raw `damagePerAttack`
- Tests: `SC2DataTest` (new), `DamageCalculatorTest` (new), additions to `EmulatedGameTest`

**Explicitly out of scope:**
- Ground vs air weapon distinction (no air units in emulator yet)
- Shield regeneration (E8+)
- Additional unit types beyond those already in `SC2Data`
- Damage vs buildings (Nexus, Pylon etc.)

---

## Part 1: `UnitAttribute` enum

New file `domain/UnitAttribute.java` — pure Java, no framework imports.

```java
package io.quarkmind.domain;

public enum UnitAttribute {
    LIGHT, ARMORED, BIOLOGICAL, MECHANICAL, PSIONIC, MASSIVE, STRUCTURE
}
```

Lives in `domain/` alongside `UnitType`, `BuildingType`, `SC2Data`.

---

## Part 2: SC2Data — new methods and corrected stats

### 2a: Corrected `maxHealth` values

| Unit | Old (default 100) | Correct |
|---|---|---|
| Immortal | 100 | **200** |
| Marine | 100 | **45** |
| Marauder | 100 | **125** |
| Roach | 100 | **145** |
| Hydralisk | 100 | **90** |

Units already correct: Probe (45), Zealot (100), Stalker (80).

### 2b: `unitAttributes(UnitType)`

```java
public static Set<UnitAttribute> unitAttributes(UnitType type) {
    return switch (type) {
        case PROBE     -> Set.of(LIGHT, MECHANICAL);
        case ZEALOT    -> Set.of(LIGHT, BIOLOGICAL);
        case STALKER   -> Set.of(ARMORED, MECHANICAL);
        case IMMORTAL  -> Set.of(ARMORED, MECHANICAL, MASSIVE);
        case OBSERVER  -> Set.of(ARMORED, MECHANICAL);
        case MARINE    -> Set.of(LIGHT, BIOLOGICAL);
        case MARAUDER  -> Set.of(BIOLOGICAL, ARMORED);
        case ROACH     -> Set.of(ARMORED, BIOLOGICAL);
        case HYDRALISK -> Set.of(LIGHT, BIOLOGICAL);
        default        -> Set.of();
    };
}
```

### 2c: `armour(UnitType)`

```java
public static int armour(UnitType type) {
    return switch (type) {
        case ZEALOT, STALKER, IMMORTAL, MARAUDER, ROACH -> 1;
        default -> 0;   // Probe, Marine, Hydralisk, Observer
    };
}
```

### 2d: `bonusDamageVs(UnitType attackerType, UnitAttribute targetAttribute)`

```java
public static int bonusDamageVs(UnitType attackerType, UnitAttribute targetAttribute) {
    return switch (attackerType) {
        case STALKER  -> targetAttribute == ARMORED ? 4  : 0;
        case IMMORTAL -> targetAttribute == ARMORED ? 3  : 0;
        case MARAUDER -> targetAttribute == ARMORED ? 10 : 0;
        default       -> 0;
    };
}
```

### 2e: `hasHardenedShield(UnitType)`

```java
public static boolean hasHardenedShield(UnitType type) {
    return type == UnitType.IMMORTAL;
}
```

---

## Part 3: `DamageCalculator`

Package-private class in `sc2/emulated/`. No CDI, no framework imports. Used only by
`EmulatedGame`; tested directly from `DamageCalculatorTest` in the same package.

```java
class DamageCalculator {

    int computeEffective(UnitType attackerType, Unit target) {
        int raw = SC2Data.damagePerAttack(attackerType)
                + bonusVsTarget(attackerType, target.type());

        int afterArmour = Math.max(1, raw - SC2Data.armour(target.type()));

        if (SC2Data.hasHardenedShield(target.type()) && target.shields() > 0) {
            return Math.min(10, afterArmour);
        }
        return afterArmour;
    }

    private int bonusVsTarget(UnitType attackerType, UnitType targetType) {
        return SC2Data.unitAttributes(targetType).stream()
            .mapToInt(attr -> SC2Data.bonusDamageVs(attackerType, attr))
            .max()
            .orElse(0);
    }
}
```

**Key rules:**
- **Minimum 1 damage** after armour — armour never fully negates an attack
- **Hardened Shield** — active when `target.shields() > 0` at tick-start (two-pass
  model uses shield state before any damage this tick is applied)
- **Bonus stacking** — takes `max` across all matching target attributes, not sum.
  In practice only one bonus fires per attacker; `max` is the correct SC2 rule

---

## Part 4: `EmulatedGame.resolveCombat()` change

One field added to `EmulatedGame`:

```java
private final DamageCalculator damageCalculator = new DamageCalculator();
```

In `resolveCombat()`, the damage collection (Step 2) changes from:

```java
// Before
pending.merge(target.tag(), SC2Data.damagePerAttack(attacker.type()), Integer::sum);
```

to:

```java
// After — target Unit is already in scope from nearestInRange
pending.merge(target.tag(), damageCalculator.computeEffective(attacker.type(), target), Integer::sum);
```

Applied to both the friendly-fires-at-enemy and enemy-fires-at-friendly loops. No other
changes to `resolveCombat()` — the pending map, two-pass apply, death removal, and
cooldown reset are all unchanged.

---

## Part 5: Testing

### `SC2DataTest` (plain JUnit, new)

Fast, no Quarkus. Verifies every new SC2Data method and all corrected values.

| Test | Asserts |
|---|---|
| `zealotAttributes` | `{LIGHT, BIOLOGICAL}` |
| `stalkerAttributes` | `{ARMORED, MECHANICAL}` |
| `immortalAttributes` | `{ARMORED, MECHANICAL, MASSIVE}` |
| `marineAttributes` | `{LIGHT, BIOLOGICAL}` |
| `roachAttributes` | `{ARMORED, BIOLOGICAL}` |
| `immortalHasHardenedShield` | `true` |
| `stalkerHasNoHardenedShield` | `false` |
| `stalkerArmour` | `1` |
| `marineArmour` | `0` |
| `zealotArmour` | `1` |
| `stalkerBonusVsArmored` | `4` |
| `stalkerBonusVsLight` | `0` |
| `marauderBonusVsArmored` | `10` |
| `immortalBonusVsArmored` | `3` |
| `probeBonusVsArmored` | `0` |
| `correctedHp_immortal` | `200` |
| `correctedHp_marine` | `45` |
| `correctedHp_marauder` | `125` |
| `correctedHp_roach` | `145` |
| `correctedHp_hydralisk` | `90` |

### `DamageCalculatorTest` (plain JUnit, new)

Each test documents one concrete damage calculation.

| Test | Attacker → Target | Expected |
|---|---|---|
| `stalkerVsMarine` | Stalker→Marine (Light, 0 armour) | 13+0−0 = **13** |
| `stalkerVsMarauder` | Stalker→Marauder (Armored, 1 armour) | 13+4−1 = **16** |
| `stalkerVsRoach` | Stalker→Roach (Armored, 1 armour) | 13+4−1 = **16** |
| `marauderVsMarine` | Marauder→Marine | 10+0−0 = **10** |
| `marauderVsStalker` | Marauder→Stalker (Armored, 1 armour) | 10+10−1 = **19** |
| `immortalVsRoach` | Immortal→Roach (Armored, 1 armour) | 20+3−1 = **22** |
| `zealotVsMarine` | Zealot→Marine | 8+0−0 = **8** |
| `zealotVsStalker` | Zealot→Stalker (Armored, 1 armour) | 8+0−1 = **7** |
| `hardenedShield_largeHit` | Stalker→shielded Immortal (13+4−1=16, then capped) | **10** |
| `hardenedShield_smallHit` | Probe→shielded Immortal (5+0−1=4, under cap) | **4** |
| `hardenedShield_unshielded` | Stalker→Immortal with 0 shields (13+4−1=16, no cap) | **16** |

Note: the `Math.max(1, ...)` armour floor is a defensive guard for future unit types —
it cannot be triggered with the current roster (minimum effective damage is Probe vs
Stalker: 5+0−1=4). No test is added for it; it will be exercised when a high-armour
unit is introduced.

### `EmulatedGameTest` additions (plain JUnit, existing class)

End-to-end through `tick()` — verifies the full pipeline picks up the new calculator.

| Test | Asserts |
|---|---|
| `stalkerDealsCorrectDamageVsArmored` | Stalker vs Roach: 16 damage (not 13) |
| `immortalShieldedCapsDamageAt10` | Stalker vs shielded Immortal: shield reduced by 10 |
| `immortalUnshieldedTakesFullDamage` | Stalker vs Immortal 0 shields: 16 damage to HP |
| `armourReducesDamage` | Zealot vs Stalker: 7 damage (not 8) |
| `spawnedMarineHasCorrectHp` | spawned Marine: `health == 45` |
| `spawnedImmortalHasCorrectHp` | spawned Immortal: `health == 200` |

### Integration / E2E

No new `@QuarkusTest` or browser tests required — `DroolsTacticsTaskIT` tests intent
types only (not damage numbers), and `VisualizerRenderTest` uses health tinting
thresholds (not exact HP). The existing suites provide regression coverage at those
levels.

One integration assertion worth adding to `QaEndpointsTest` or an emulated engine test:
`GET /q/gamestate` returns correct `maxHealth` for units spawned in the emulated profile.
This catches serialisation regressions at the REST boundary.

---

## Part 6: GitHub Issue Structure

| Type | Title |
|---|---|
| Epic | E5: Damage Types, Armour & Hardened Shield |
| Issue | #E5-1: `UnitAttribute` enum in `domain/` |
| Issue | #E5-2: SC2Data — `unitAttributes`, `armour`, `bonusDamageVs`, `hasHardenedShield` + HP corrections |
| Issue | #E5-3: `DamageCalculator` — `sc2/emulated/` |
| Issue | #E5-4: `EmulatedGame.resolveCombat()` — use `DamageCalculator` |
| Issue | #E5-5: Tests — `SC2DataTest`, `DamageCalculatorTest`, `EmulatedGameTest` additions |

---

## Context Links

- E4 spec: `docs/superpowers/specs/2026-04-14-sc2-emulation-e4-design.md`
- Library research: `docs/library-research.md`
- GitHub: mdproctor/quarkmind
