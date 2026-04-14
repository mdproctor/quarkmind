package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;

/**
 * Computes effective damage per hit for the emulated combat engine.
 *
 * <p>Formula: {@code max(1, rawDamage + bonusVsAttributes - armour)},
 * then capped at 10 if the target is an Immortal with shields remaining
 * (Hardened Shield passive).
 */
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
