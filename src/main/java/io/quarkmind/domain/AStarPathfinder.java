package io.quarkmind.domain;

import java.util.*;

/**
 * Stateless 8-directional A* pathfinder.
 * Returns tile-centre world coordinates (x+0.5f, y+0.5f) excluding the start tile.
 * Reusable by both the emulated engine and the real SC2 engine.
 */
public final class AStarPathfinder {

    private static final int[][] DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1,-1}, {-1, 1}, {-1,-1}
    };
    private static final double DIAG = Math.sqrt(2);

    public List<Point2d> findPath(WalkabilityGrid grid, Point2d from, Point2d to) {
        int[] start = nearestWalkable(grid, (int) from.x(), (int) from.y());
        int[] goal  = nearestWalkable(grid, (int) to.x(),   (int) to.y());
        final int sx = start[0], sy = start[1];
        final int gx = goal[0],  gy = goal[1];

        if (sx == gx && sy == gy) return List.of();

        record Node(int x, int y, double g, Node parent) {}

        PriorityQueue<Node> open = new PriorityQueue<>(
            Comparator.comparingDouble(n ->
                n.g() + Math.sqrt((n.x() - gx) * (double)(n.x() - gx)
                                + (n.y() - gy) * (double)(n.y() - gy))));

        boolean[][] closed = new boolean[grid.width()][grid.height()];
        open.add(new Node(sx, sy, 0, null));

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (closed[cur.x()][cur.y()]) continue;
            closed[cur.x()][cur.y()] = true;

            if (cur.x() == gx && cur.y() == gy) {
                Deque<Point2d> path = new ArrayDeque<>();
                for (Node n = cur; n.parent() != null; n = n.parent()) {
                    path.addFirst(new Point2d(n.x() + 0.5f, n.y() + 0.5f));
                }
                return new ArrayList<>(path);
            }

            for (int[] d : DIRS) {
                int nx = cur.x() + d[0];
                int ny = cur.y() + d[1];
                if (!grid.isWalkable(nx, ny) || closed[nx][ny]) continue;
                double cost = (d[0] != 0 && d[1] != 0) ? DIAG : 1.0;
                open.add(new Node(nx, ny, cur.g() + cost, cur));
            }
        }
        return List.of();
    }

    private static int[] nearestWalkable(WalkabilityGrid grid, int x, int y) {
        if (grid.isWalkable(x, y)) return new int[]{x, y};
        for (int r = 1; r <= Math.max(grid.width(), grid.height()); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) == r || Math.abs(dy) == r) {
                        if (grid.isWalkable(x + dx, y + dy))
                            return new int[]{x + dx, y + dy};
                    }
                }
            }
        }
        return new int[]{x, y};
    }
}
