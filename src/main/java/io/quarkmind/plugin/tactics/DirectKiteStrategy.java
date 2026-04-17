package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.Unit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
@Named("direct")
public class DirectKiteStrategy implements KiteStrategy {
    static final double KITE_STEP = 1.0;

    @Override
    public Point2d retreatTarget(Unit unit, List<Unit> enemies, TerrainGrid terrain) {
        Unit nearest = enemies.stream()
            .min(Comparator.comparingDouble(e -> distance(unit.position(), e.position())))
            .orElseThrow();
        double dx = unit.position().x() - nearest.position().x();
        double dy = unit.position().y() - nearest.position().y();
        double len = Math.sqrt(dx*dx + dy*dy);
        if (len < 0.001) return unit.position();
        return new Point2d(
            (float)(unit.position().x() + dx/len * KITE_STEP),
            (float)(unit.position().y() + dy/len * KITE_STEP));
    }

    static double distance(Point2d a, Point2d b) {
        double dx = a.x()-b.x(), dy = a.y()-b.y();
        return Math.sqrt(dx*dx + dy*dy);
    }
}
