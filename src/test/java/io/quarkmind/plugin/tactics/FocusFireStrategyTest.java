package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FocusFireStrategyTest {
    private static Unit stalker(String tag, Point2d pos) {
        return new Unit(tag, UnitType.STALKER, pos, 80, 80, 80, 80, 0, 0);
    }
    private static Unit enemy(String tag, Point2d pos, int hp, int shields) {
        return new Unit(tag, UnitType.ZEALOT, pos, hp, 100, shields, 50, 0, 0);
    }

    @Test void lowestHp_allAttackLowestHpEnemy() {
        List<Unit> attackers = List.of(stalker("u-0", new Point2d(5,5)), stalker("u-1", new Point2d(5,6)));
        List<Unit> enemies   = List.of(enemy("e-strong", new Point2d(10,10), 100, 50),
                                       enemy("e-weak",   new Point2d(11,11),  10,  0));
        Map<String, Point2d> result = new LowestHpFocusFireStrategy().assignTargets(attackers, enemies);
        assertThat(result.get("u-0")).isEqualTo(new Point2d(11,11));
        assertThat(result.get("u-1")).isEqualTo(new Point2d(11,11));
    }
    @Test void lowestHp_singleEnemy() {
        Map<String, Point2d> result = new LowestHpFocusFireStrategy().assignTargets(
            List.of(stalker("u-0", new Point2d(5,5))),
            List.of(enemy("e-0", new Point2d(10,10), 80, 80)));
        assertThat(result.get("u-0")).isEqualTo(new Point2d(10,10));
    }

    // OverkillRedirectFocusFireStrategy
    // SC2Data.damagePerAttack(STALKER) = 13

    @Test void overkill_splitsMinimumAttackersOnPrimary() {
        // 5 Stalkers (13 dmg each). Weak enemy: 20 HP+shields. Strong: 150.
        // ceil(20/13) = 2 attackers cover primary. Remaining 3 → strong.
        List<Unit> attackers = java.util.stream.IntStream.range(0, 5)
            .mapToObj(i -> stalker("u-" + i, new Point2d(5, 5 + i))).toList();
        List<Unit> enemies = List.of(
            enemy("e-weak",   new Point2d(10, 10), 10, 10),   // 20 total
            enemy("e-strong", new Point2d(11, 11), 100, 50));  // 150 total
        Map<String, Point2d> result =
            new OverkillRedirectFocusFireStrategy().assignTargets(attackers, enemies);
        long onWeak   = result.values().stream().filter(p -> p.equals(new Point2d(10, 10))).count();
        long onStrong = result.values().stream().filter(p -> p.equals(new Point2d(11, 11))).count();
        assertThat(onWeak).isEqualTo(2);
        assertThat(onStrong).isEqualTo(3);
    }

    @Test void overkill_allAttackPrimaryWhenFullVolleyNeeded() {
        // 3 Stalkers × 13 = 39. Primary has 50 HP+shields. All commit to primary.
        List<Unit> attackers = List.of(stalker("u-0", new Point2d(5, 5)),
                                       stalker("u-1", new Point2d(5, 6)),
                                       stalker("u-2", new Point2d(5, 7)));
        List<Unit> enemies = List.of(enemy("e-0", new Point2d(10, 10), 40, 10)); // 50 total
        Map<String, Point2d> result =
            new OverkillRedirectFocusFireStrategy().assignTargets(attackers, enemies);
        assertThat(result.values()).allMatch(p -> p.equals(new Point2d(10, 10)));
    }

    @Test void overkill_singleEnemy_allAttackIt() {
        Map<String, Point2d> result = new OverkillRedirectFocusFireStrategy().assignTargets(
            List.of(stalker("u-0", new Point2d(5, 5))),
            List.of(enemy("e-0", new Point2d(10, 10), 80, 80)));
        assertThat(result.get("u-0")).isEqualTo(new Point2d(10, 10));
    }
}
