package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static io.quarkmind.domain.UnitAttribute.*;

class SC2DataTest {

    @Test void zealotAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.ZEALOT)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void stalkerAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.STALKER)).containsExactlyInAnyOrder(ARMORED, MECHANICAL);
    }
    @Test void immortalAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.IMMORTAL)).containsExactlyInAnyOrder(ARMORED, MECHANICAL, MASSIVE);
    }
    @Test void marineAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.MARINE)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void roachAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.ROACH)).containsExactlyInAnyOrder(ARMORED, BIOLOGICAL);
    }
    @Test void hydraliskAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.HYDRALISK)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void probeAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.PROBE)).containsExactlyInAnyOrder(LIGHT, MECHANICAL);
    }

    @Test void immortalHasHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.IMMORTAL)).isTrue();
    }
    @Test void stalkerHasNoHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.STALKER)).isFalse();
    }
    @Test void zealotHasNoHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.ZEALOT)).isFalse();
    }

    @Test void stalkerArmour() { assertThat(SC2Data.armour(UnitType.STALKER)).isEqualTo(1); }
    @Test void zealotArmour()  { assertThat(SC2Data.armour(UnitType.ZEALOT)).isEqualTo(1); }
    @Test void immortalArmour(){ assertThat(SC2Data.armour(UnitType.IMMORTAL)).isEqualTo(1); }
    @Test void marauderArmour(){ assertThat(SC2Data.armour(UnitType.MARAUDER)).isEqualTo(1); }
    @Test void roachArmour()   { assertThat(SC2Data.armour(UnitType.ROACH)).isEqualTo(1); }
    @Test void marineArmour()  { assertThat(SC2Data.armour(UnitType.MARINE)).isEqualTo(0); }
    @Test void probeArmour()   { assertThat(SC2Data.armour(UnitType.PROBE)).isEqualTo(0); }
    @Test void hydraliskArmour() { assertThat(SC2Data.armour(UnitType.HYDRALISK)).isEqualTo(0); }

    @Test void stalkerBonusVsArmored()  { assertThat(SC2Data.bonusDamageVs(UnitType.STALKER,  ARMORED)).isEqualTo(4); }
    @Test void stalkerBonusVsLight()    { assertThat(SC2Data.bonusDamageVs(UnitType.STALKER,  LIGHT)).isEqualTo(0); }
    @Test void marauderBonusVsLight()   { assertThat(SC2Data.bonusDamageVs(UnitType.MARAUDER, LIGHT)).isEqualTo(0); }
    @Test void marauderBonusVsArmored() { assertThat(SC2Data.bonusDamageVs(UnitType.MARAUDER, ARMORED)).isEqualTo(10); }
    @Test void immortalBonusVsArmored() { assertThat(SC2Data.bonusDamageVs(UnitType.IMMORTAL, ARMORED)).isEqualTo(3); }
    @Test void probeBonusVsArmored()    { assertThat(SC2Data.bonusDamageVs(UnitType.PROBE,    ARMORED)).isEqualTo(0); }
    @Test void zealotBonusVsArmored()   { assertThat(SC2Data.bonusDamageVs(UnitType.ZEALOT,   ARMORED)).isEqualTo(0); }

    @Test void correctedHp_immortal()  { assertThat(SC2Data.maxHealth(UnitType.IMMORTAL)).isEqualTo(200); }
    @Test void correctedHp_marine()    { assertThat(SC2Data.maxHealth(UnitType.MARINE)).isEqualTo(45); }
    @Test void correctedHp_marauder()  { assertThat(SC2Data.maxHealth(UnitType.MARAUDER)).isEqualTo(125); }
    @Test void correctedHp_roach()     { assertThat(SC2Data.maxHealth(UnitType.ROACH)).isEqualTo(145); }
    @Test void correctedHp_hydralisk() { assertThat(SC2Data.maxHealth(UnitType.HYDRALISK)).isEqualTo(90); }

    @Test
    void damagePerAttackDefinedForProtossUnits() {
        assertThat(SC2Data.damagePerAttack(UnitType.PROBE)).isEqualTo(5);
        assertThat(SC2Data.damagePerAttack(UnitType.ZEALOT)).isEqualTo(8);
        assertThat(SC2Data.damagePerAttack(UnitType.STALKER)).isEqualTo(13);
        assertThat(SC2Data.damagePerAttack(UnitType.IMMORTAL)).isEqualTo(20);
    }

    @Test
    void damagePerAttackDefinedForTerranAndZergUnits() {
        assertThat(SC2Data.damagePerAttack(UnitType.MARINE)).isEqualTo(6);
        assertThat(SC2Data.damagePerAttack(UnitType.MARAUDER)).isEqualTo(10);
        assertThat(SC2Data.damagePerAttack(UnitType.ROACH)).isEqualTo(9);
        assertThat(SC2Data.damagePerAttack(UnitType.HYDRALISK)).isEqualTo(12);
    }

    @Test
    void attackCooldownInTicksDefinedForAllCombatUnits() {
        assertThat(SC2Data.attackCooldownInTicks(UnitType.MARINE)).isEqualTo(1);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.HYDRALISK)).isEqualTo(1);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.PROBE)).isEqualTo(2);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.ZEALOT)).isEqualTo(2);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.STALKER)).isEqualTo(3);
    }

    @Test
    void defaultCooldownAppliesForUnknownType() {
        assertThat(SC2Data.attackCooldownInTicks(UnitType.UNKNOWN)).isEqualTo(2);
    }

    @Test
    void defaultDamageAppliesForUnknownType() {
        assertThat(SC2Data.damagePerAttack(UnitType.UNKNOWN)).isEqualTo(5);
    }
}
