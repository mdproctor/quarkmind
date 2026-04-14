package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SC2DataTest {

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