package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

public interface MovementStrategy {

    /** Advance one tick: return new world position for a unit moving toward target. */
    Point2d advance(String unitTag, Point2d current, Point2d target, double speed);

    /** Called when a unit is permanently removed (dead or transferred to staging). */
    default void clearUnit(String unitTag) {}

    /** Called from EmulatedGame.reset() — clear ALL per-unit state. */
    default void reset() {}
}
