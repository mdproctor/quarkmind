package io.quarkmind.plugin.tactics;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.*;

@ApplicationScoped
@Named("overkill-redirect")
public class OverkillRedirectFocusFireStrategy implements FocusFireStrategy {

    @Override
    public Map<String, Point2d> assignTargets(List<Unit> attackers, List<Unit> enemies) {
        List<Unit> sorted = enemies.stream()
            .sorted(Comparator.comparingInt(e -> e.health() + e.shields())).toList();
        Unit primary   = sorted.get(0);
        int  primaryHp = primary.health() + primary.shields();
        Map<String, Point2d> assignments = new HashMap<>();
        int accumulated = 0;
        boolean primaryKillable = false;
        for (Unit attacker : attackers) {
            if (!primaryKillable) {
                assignments.put(attacker.tag(), primary.position());
                accumulated += SC2Data.damagePerAttack(attacker.type());
                if (accumulated >= primaryHp) primaryKillable = true;
            } else {
                Point2d target = sorted.size() > 1 ? sorted.get(1).position() : primary.position();
                assignments.put(attacker.tag(), target);
            }
        }
        return assignments;
    }
}
