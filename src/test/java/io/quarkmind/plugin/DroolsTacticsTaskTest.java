package io.quarkmind.plugin;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsTacticsTaskTest {

    // ---- Helpers ----

    private static Unit unit(String tag, UnitType type, Point2d pos) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, 0, 0);
    }

    private static Unit unit(String tag, UnitType type, Point2d pos, int cooldown) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, cooldown, 0);
    }

    private static Unit enemy(Point2d pos) {
        return new Unit("e-0", UnitType.ZEALOT, pos, 100, 100, 50, 50, 0, 0);
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

    // ---- computeBlinkReadyTags ----

    @Test
    void computeBlinkReadyTagsReturnsStalkerWithCooldownZero() {
        List<Unit> army = List.of(
            new Unit("s-0", UnitType.STALKER, new Point2d(0,0), 80, 80, 80, 80, 0, 0),  // blink ready
            new Unit("s-1", UnitType.STALKER, new Point2d(0,0), 80, 80, 80, 80, 0, 5),  // on blink cooldown
            new Unit("z-0", UnitType.ZEALOT,  new Point2d(0,0), 100, 100, 50, 50, 0, 0) // not a Stalker
        );
        Set<String> result = DroolsTacticsTask.computeBlinkReadyTags(army);
        assertThat(result).containsExactly("s-0");
    }

    // ---- computeShieldsLowTags ----

    @Test
    void computeShieldsLowTagsReturnsBelowTwentyFivePercent() {
        List<Unit> army = List.of(
            new Unit("s-0", UnitType.STALKER, new Point2d(0,0), 80, 80, 19, 80, 0, 0), // 19 < 20 (25% of 80)
            new Unit("s-1", UnitType.STALKER, new Point2d(0,0), 80, 80, 20, 80, 0, 0), // exactly 25%, NOT low
            new Unit("s-2", UnitType.STALKER, new Point2d(0,0), 80, 80,  0, 80, 0, 0)  // 0 shields — low
        );
        Set<String> result = DroolsTacticsTask.computeShieldsLowTags(army);
        assertThat(result).containsExactlyInAnyOrder("s-0", "s-2");
    }

}
