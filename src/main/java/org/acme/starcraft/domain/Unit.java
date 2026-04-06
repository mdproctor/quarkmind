package org.acme.starcraft.domain;

public record Unit(String tag, UnitType type, Point2d position, int health, int maxHealth) {}
