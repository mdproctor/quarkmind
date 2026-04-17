package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DamageCalculatorTest {

    private final DamageCalculator calc = new DamageCalculator();

    /** Unit of given type at full HP and shields. */
    private Unit full(UnitType type) {
        int hp = SC2Data.maxHealth(type);
        int sh = SC2Data.maxShields(type);
        return new Unit("t", type, new Point2d(0, 0), hp, hp, sh, sh, 0);
    }

    /** Unit with custom shield value (HP at max). */
    private Unit withShields(UnitType type, int shields) {
        int hp = SC2Data.maxHealth(type);
        return new Unit("t", type, new Point2d(0, 0), hp, hp, shields, SC2Data.maxShields(type), 0);
    }

    // ---- no bonus damage ----

    @Test void stalkerVsMarine() {
        // 13 + 0 (no bonus vs Light) - 0 (Marine armour) = 13
        assertThat(calc.computeEffective(UnitType.STALKER, full(UnitType.MARINE))).isEqualTo(13);
    }

    @Test void zealotVsMarine() {
        // 8 + 0 - 0 = 8
        assertThat(calc.computeEffective(UnitType.ZEALOT, full(UnitType.MARINE))).isEqualTo(8);
    }

    @Test void marauderVsMarine() {
        // 10 + 0 (no bonus vs Light) - 0 = 10
        assertThat(calc.computeEffective(UnitType.MARAUDER, full(UnitType.MARINE))).isEqualTo(10);
    }

    // ---- armour reduction ----

    @Test void zealotVsStalker() {
        // 8 + 0 (no bonus vs Armored) - 1 (Stalker armour) = 7
        assertThat(calc.computeEffective(UnitType.ZEALOT, full(UnitType.STALKER))).isEqualTo(7);
    }

    @Test void zealotVsZealot() {
        // 8 + 0 - 1 (Zealot armour) = 7
        assertThat(calc.computeEffective(UnitType.ZEALOT, full(UnitType.ZEALOT))).isEqualTo(7);
    }

    // ---- bonus damage vs Armored ----

    @Test void stalkerVsMarauder() {
        // 13 + 4 (vs Armored) - 1 (Marauder armour) = 16
        assertThat(calc.computeEffective(UnitType.STALKER, full(UnitType.MARAUDER))).isEqualTo(16);
    }

    @Test void stalkerVsRoach() {
        // 13 + 4 (vs Armored) - 1 (Roach armour) = 16
        assertThat(calc.computeEffective(UnitType.STALKER, full(UnitType.ROACH))).isEqualTo(16);
    }

    @Test void marauderVsStalker() {
        // 10 + 10 (vs Armored) - 1 (Stalker armour) = 19
        assertThat(calc.computeEffective(UnitType.MARAUDER, full(UnitType.STALKER))).isEqualTo(19);
    }

    @Test void immortalVsRoach() {
        // 20 + 3 (vs Armored) - 1 (Roach armour) = 22
        assertThat(calc.computeEffective(UnitType.IMMORTAL, full(UnitType.ROACH))).isEqualTo(22);
    }

    // ---- Hardened Shield (Immortal with shields > 0) ----

    @Test void hardenedShield_largeHit() {
        // Stalker vs shielded Immortal: 13+4-1=16, Hardened Shield caps to 10
        assertThat(calc.computeEffective(UnitType.STALKER, full(UnitType.IMMORTAL))).isEqualTo(10);
    }

    @Test void hardenedShield_smallHit() {
        // Probe vs shielded Immortal: 5+0-1=4, under the 10 cap — no clamping
        assertThat(calc.computeEffective(UnitType.PROBE, full(UnitType.IMMORTAL))).isEqualTo(4);
    }

    @Test void hardenedShield_unshielded_takesFullDamage() {
        // Stalker vs Immortal with 0 shields: 13+4-1=16, no Hardened Shield active
        assertThat(calc.computeEffective(UnitType.STALKER, withShields(UnitType.IMMORTAL, 0)))
            .isEqualTo(16);
    }

    @Test void hardenedShield_doesNotActivateOnNonImmortal() {
        // Stalker vs Zealot (shielded but no Hardened Shield): 13+0-1=12, no cap
        assertThat(calc.computeEffective(UnitType.STALKER, full(UnitType.ZEALOT))).isEqualTo(12);
    }
}
