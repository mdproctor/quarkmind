package org.acme.starcraft.domain;

public record Building(String tag, BuildingType type, Point2d position, int health, int maxHealth, boolean isComplete) {}
