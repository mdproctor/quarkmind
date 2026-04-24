package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;

import java.util.*;

public final class UnitOrderTracker {

    private static final float ARRIVAL_THRESHOLD = 0.5f;

    private final Map<String, UnitOrder> activeOrders = new HashMap<>();
    private final List<UnitOrder> pending = new ArrayList<>();
    private int pendingCursor = 0;

    public void loadOrders(List<UnitOrder> orders) {
        pending.clear();
        pending.addAll(orders);
        pendingCursor = 0;
        activeOrders.clear();
    }

    /**
     * Advance all units one tick.
     * @param currentLoop  current game loop after advancing
     * @param positions    mutable map of tag → current position (updated in-place)
     * @param unitTypes    map of tag → UnitType for speed lookup
     */
    public void advance(long currentLoop, Map<String, Point2d> positions,
                        Map<String, UnitType> unitTypes) {
        while (pendingCursor < pending.size()
               && pending.get(pendingCursor).loop() <= currentLoop) {
            UnitOrder o = pending.get(pendingCursor++);
            activeOrders.put(o.unitTag(), o);
        }

        float secondsPerTick = 22 / 22.4f;  // 22 loops per tick at Faster speed

        for (Map.Entry<String, UnitOrder> entry : activeOrders.entrySet()) {
            String tag = entry.getKey();
            UnitOrder order = entry.getValue();
            Point2d current = positions.get(tag);
            if (current == null) continue;

            Point2d target = resolveTarget(order, positions);
            if (target == null) continue;

            float dx = target.x() - current.x();
            float dy = target.y() - current.y();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist <= ARRIVAL_THRESHOLD) continue;

            float speed = (float) SC2Data.unitSpeed(
                unitTypes.getOrDefault(tag, UnitType.UNKNOWN));
            float step = speed * secondsPerTick;

            if (step >= dist) {
                positions.put(tag, target);
            } else {
                float ratio = step / dist;
                positions.put(tag, new Point2d(
                    current.x() + dx * ratio,
                    current.y() + dy * ratio));
            }
        }
    }

    public void removeUnit(String tag) {
        activeOrders.remove(tag);
    }

    public void reset() {
        activeOrders.clear();
        pendingCursor = 0;
    }

    private static Point2d resolveTarget(UnitOrder order, Map<String, Point2d> positions) {
        if (order.isMove())   return order.targetPos();
        if (order.isFollow()) return positions.get(order.targetUnitTag());
        return null;
    }
}
