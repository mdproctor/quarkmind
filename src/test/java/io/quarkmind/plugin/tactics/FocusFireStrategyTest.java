package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FocusFireStrategyTest {
    private static Unit stalker(String tag, Point2d pos) {
        return new Unit(tag, UnitType.STALKER, pos, 80, 80, 80, 80, 0);
    }
    private static Unit enemy(String tag, Point2d pos, int hp, int shields) {
        return new Unit(tag, UnitType.ZEALOT, pos, hp, 100, shields, 50, 0);
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
}
