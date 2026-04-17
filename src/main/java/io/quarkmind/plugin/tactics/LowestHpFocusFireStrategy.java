package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.*;

@ApplicationScoped
@Named("lowest-hp")
public class LowestHpFocusFireStrategy implements FocusFireStrategy {
    @Override
    public Map<String, Point2d> assignTargets(List<Unit> attackers, List<Unit> enemies) {
        Point2d target = enemies.stream()
            .min(Comparator.comparingInt(e -> e.health() + e.shields()))
            .map(Unit::position).orElseThrow();
        Map<String, Point2d> assignments = new HashMap<>();
        attackers.forEach(u -> assignments.put(u.tag(), target));
        return assignments;
    }
}
