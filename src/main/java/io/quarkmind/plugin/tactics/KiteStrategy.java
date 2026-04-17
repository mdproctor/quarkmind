package io.quarkmind.plugin.tactics;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.Unit;
import java.util.List;
public interface KiteStrategy {
    /** @param terrain nullable — fall back to terrain-blind when null */
    Point2d retreatTarget(Unit unit, List<Unit> enemies, TerrainGrid terrain);
}
