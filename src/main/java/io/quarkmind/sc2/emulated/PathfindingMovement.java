package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.AStarPathfinder;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.WalkabilityGrid;

import java.util.*;

/** A* based movement: computes and follows waypoint queues per unit. */
public class PathfindingMovement implements MovementStrategy {

    private final WalkabilityGrid grid;
    private final AStarPathfinder pathfinder = new AStarPathfinder();
    private final Map<String, Deque<Point2d>> waypoints   = new HashMap<>();
    private final Map<String, Point2d>        lastTargets = new HashMap<>();

    public PathfindingMovement(WalkabilityGrid grid) { this.grid = grid; }

    @Override
    public Point2d advance(String unitTag, Point2d current, Point2d target, double speed) {
        // Recompute path when target changes
        if (!target.equals(lastTargets.get(unitTag))) {
            List<Point2d> path = pathfinder.findPath(grid, current, target);
            waypoints.put(unitTag, new ArrayDeque<>(path));
            lastTargets.put(unitTag, target);
        }

        Deque<Point2d> queue = waypoints.get(unitTag);
        if (queue == null || queue.isEmpty()) {
            // No path (unreachable or arrived) — fall back to direct movement
            return EmulatedGame.stepToward(current, target, speed);
        }

        Point2d next   = queue.peek();
        Point2d newPos = EmulatedGame.stepToward(current, next, speed);

        // Advance to next waypoint when current one is reached (within 0.1 tiles)
        if (EmulatedGame.distance(newPos, next) < 0.1) {
            queue.poll();
        }
        return newPos;
    }

    @Override
    public void clearUnit(String unitTag) {
        waypoints.remove(unitTag);
        lastTargets.remove(unitTag);
    }

    @Override
    public void reset() {
        waypoints.clear();
        lastTargets.clear();
    }
}
