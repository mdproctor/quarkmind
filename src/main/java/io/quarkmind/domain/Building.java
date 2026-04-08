package io.quarkmind.domain;

public record Building(String tag, BuildingType type, Point2d position, int health, int maxHealth, boolean isComplete) {}
