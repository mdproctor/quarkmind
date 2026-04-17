package io.quarkmind.plugin;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DroolsTacticsTaskTest {

    // ---- Helpers ----

    private static Unit unit(String tag, UnitType type, Point2d pos) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, 0);
    }

    private static Unit unit(String tag, UnitType type, Point2d pos, int cooldown) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, cooldown);
    }

    private static Unit enemy(Point2d pos) {
        return new Unit("e-0", UnitType.ZEALOT, pos, 100, 100, 50, 50, 0);
    }

    // ---- computeInRangeTags: per-unit range ----

    @Test
    void stalkerAt4_5_isInRange() {
        // Stalker range = 5.0; distance 4.5 → in range
        Unit s = unit("s-0", UnitType.STALKER, new Point2d(10, 10));
        Unit e = enemy(new Point2d(14.5f, 10)); // distance exactly 4.5
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(s), List.of(e));
        assertThat(result).contains("s-0");
    }

    @Test
    void stalkerAt5_5_isNotInRange() {
        // Stalker range = 5.0; distance 5.5 → out of range
        Unit s = unit("s-0", UnitType.STALKER, new Point2d(10, 10));
        Unit e = enemy(new Point2d(15.5f, 10)); // distance exactly 5.5
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(s), List.of(e));
        assertThat(result).doesNotContain("s-0");
    }

    @Test
    void zealotAt0_4_isInRange() {
        // Zealot range = 0.5; distance 0.4 → in range
        Unit z = unit("z-0", UnitType.ZEALOT, new Point2d(10, 10));
        Unit e = enemy(new Point2d(10.4f, 10));
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(z), List.of(e));
        assertThat(result).contains("z-0");
    }

    @Test
    void zealotAt0_6_isNotInRange() {
        // Zealot range = 0.5; distance 0.6 → out of range
        Unit z = unit("z-0", UnitType.ZEALOT, new Point2d(10, 10));
        Unit e = enemy(new Point2d(10.6f, 10));
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(z), List.of(e));
        assertThat(result).doesNotContain("z-0");
    }

    // ---- selectFocusTarget ----

    @Test
    void selectFocusTarget_returnsLowestCombinedHpAndShields() {
        Unit strong = new Unit("e-strong", UnitType.ZEALOT, new Point2d(10,10), 100, 100, 50, 50, 0); // 150
        Unit weak   = new Unit("e-weak",   UnitType.ZEALOT, new Point2d(10,10),  20, 100,  0, 50, 0); //  20
        Unit mid    = new Unit("e-mid",    UnitType.ZEALOT, new Point2d(10,10),  60, 100, 30, 50, 0); //  90
        var result = DroolsTacticsTask.selectFocusTarget(List.of(strong, weak, mid));
        assertThat(result).isPresent();
        assertThat(result.get().tag()).isEqualTo("e-weak");
    }

    @Test
    void selectFocusTarget_shieldsCountTowardTotal() {
        // e-1: 80 hp + 0 shields = 80 total
        // e-2: 10 hp + 50 shields = 60 total → lower, should be chosen
        Unit e1 = new Unit("e-1", UnitType.ZEALOT, new Point2d(10,10), 80, 100,  0, 50, 0);
        Unit e2 = new Unit("e-2", UnitType.ZEALOT, new Point2d(10,10), 10, 100, 50, 50, 0);
        var result = DroolsTacticsTask.selectFocusTarget(List.of(e1, e2));
        assertThat(result).isPresent();
        assertThat(result.get().tag()).isEqualTo("e-2");
    }

    @Test
    void selectFocusTarget_emptyList_returnsEmpty() {
        assertThat(DroolsTacticsTask.selectFocusTarget(List.of())).isEmpty();
    }

    // ---- computeOnCooldownTags ----

    @Test
    void computeOnCooldownTags_includesOnlyUnitsWithCooldown() {
        Unit ready  = unit("s-ready",  UnitType.STALKER, new Point2d(10, 10), 0);
        Unit kiting = unit("s-kiting", UnitType.STALKER, new Point2d(10, 10), 3);
        Set<String> result = DroolsTacticsTask.computeOnCooldownTags(List.of(ready, kiting));
        assertThat(result).containsOnly("s-kiting");
    }

    @Test
    void computeOnCooldownTags_allReady_returnsEmpty() {
        Unit r0 = unit("s-0", UnitType.STALKER, new Point2d(10,10), 0);
        Unit r1 = unit("s-1", UnitType.STALKER, new Point2d(10,10), 0);
        assertThat(DroolsTacticsTask.computeOnCooldownTags(List.of(r0, r1))).isEmpty();
    }

    // ---- kiteRetreatTarget ----

    @Test
    void kiteRetreatTarget_movesAwayOnYAxis() {
        // Unit at (10,10), enemy at (10,15): retreat direction is (0,-1), step 1.0 → (10,9)
        Point2d unitPos = new Point2d(10, 10);
        Unit e = enemy(new Point2d(10, 15));
        Point2d retreat = DroolsTacticsTask.kiteRetreatTarget(unitPos, List.of(e));
        assertThat(retreat.x()).isCloseTo(10f, within(0.01f));
        assertThat(retreat.y()).isCloseTo(9f,  within(0.01f));
    }

    @Test
    void kiteRetreatTarget_stepLengthEqualsKiteStep_diagonal() {
        // Any direction: step vector must have magnitude == KITE_STEP
        Point2d unitPos = new Point2d(10, 10);
        Unit e = enemy(new Point2d(13, 14)); // diagonal enemy
        Point2d retreat = DroolsTacticsTask.kiteRetreatTarget(unitPos, List.of(e));
        double stepLen = Math.sqrt(
            Math.pow(retreat.x() - unitPos.x(), 2) +
            Math.pow(retreat.y() - unitPos.y(), 2));
        assertThat(stepLen).isCloseTo(DroolsTacticsTask.KITE_STEP, within(0.01));
    }

    @Test
    void kiteRetreatTarget_degenerate_unitOnEnemy_returnsUnitPos() {
        Point2d unitPos = new Point2d(10, 10);
        Unit e = enemy(new Point2d(10, 10)); // exactly overlapping
        Point2d retreat = DroolsTacticsTask.kiteRetreatTarget(unitPos, List.of(e));
        assertThat(retreat).isEqualTo(unitPos);
    }

    @Test
    void kiteRetreatTarget_usesNearestEnemy() {
        // Retreat should be away from the closest enemy, not the farther one
        Point2d unitPos = new Point2d(10, 10);
        Unit near = enemy(new Point2d(10, 12)); // 2 tiles away
        Unit farEnemy = new Unit("e-far", UnitType.ZEALOT, new Point2d(10, 20), 100, 100, 50, 50, 0); // 10 tiles, distinct tag
        Point2d retreat = DroolsTacticsTask.kiteRetreatTarget(unitPos, List.of(near, farEnemy));
        // Away from near (10,12): direction (0,-1), step 1.0 → (10,9)
        assertThat(retreat.y()).isCloseTo(9f, within(0.01f));
    }
}
