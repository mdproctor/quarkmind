package org.acme.starcraft.domain;

public record Point2d(float x, float y) {

    public double distanceTo(Point2d other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
