package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Point2d;

/**
 * A movement or follow order extracted from GAME_EVENTS.
 * Exactly one of targetPos or targetUnitTag is non-null.
 */
public record UnitOrder(
    String unitTag,      // tracker-event tag: "r-{index}-{recycle}"
    long   loop,         // game loop this order was issued
    Point2d targetPos,   // non-null for move orders
    String targetUnitTag // non-null for follow/attack orders
) {
    public boolean isMove()   { return targetPos != null; }
    public boolean isFollow() { return targetUnitTag != null; }
}
