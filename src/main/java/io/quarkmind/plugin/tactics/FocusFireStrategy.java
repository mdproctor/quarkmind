package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import java.util.List;
import java.util.Map;
public interface FocusFireStrategy {
    /** @return map of attacker unit tag → target position */
    Map<String, Point2d> assignTargets(List<Unit> attackers, List<Unit> enemies);
}
