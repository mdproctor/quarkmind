package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

/** Default movement: straight-line interpolation. Preserves all pre-E7 test behaviour. */
public class DirectMovement implements MovementStrategy {

    @Override
    public Point2d advance(String unitTag, Point2d current, Point2d target, double speed) {
        return EmulatedGame.stepToward(current, target, speed);
    }
    // clearUnit and reset are no-ops (inherited defaults)
}
