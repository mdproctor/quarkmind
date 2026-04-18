package io.quarkmind.plugin.tactics;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.Unit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
@Named("terrain-aware")
public class TerrainAwareKiteStrategy implements KiteStrategy {

    // Angular offsets tried in order: ideal, ±45°, ±90°, ±135°, 180°
    private static final double[] SWEEP = {
        0, Math.PI/4, -Math.PI/4, Math.PI/2, -Math.PI/2,
        3*Math.PI/4, -3*Math.PI/4, Math.PI
    };

    @Override
    public Point2d retreatTarget(Unit unit, List<Unit> enemies, TerrainGrid terrain) {
        Unit nearest = enemies.stream()
            .min(Comparator.comparingDouble(e -> DirectKiteStrategy.distance(unit.position(), e.position())))
            .orElseThrow();
        double dx = unit.position().x() - nearest.position().x();
        double dy = unit.position().y() - nearest.position().y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) return unit.position();

        double idealAngle = Math.atan2(dy, dx);
        if (terrain == null) return candidateAt(unit.position(), idealAngle);

        for (double offset : SWEEP) {
            Point2d candidate = candidateAt(unit.position(), idealAngle + offset);
            if (terrain.isWalkable((int) candidate.x(), (int) candidate.y())) return candidate;
        }
        return unit.position(); // all 8 blocked — stay put
    }

    private static Point2d candidateAt(Point2d pos, double angle) {
        return new Point2d(
            (float)(pos.x() + Math.cos(angle) * DirectKiteStrategy.KITE_STEP),
            (float)(pos.y() + Math.sin(angle) * DirectKiteStrategy.KITE_STEP));
    }
}
