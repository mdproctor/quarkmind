package io.quarkmind.domain;

public record Unit(String tag, UnitType type, Point2d position,
                   int health, int maxHealth,
                   int shields, int maxShields,
                   int weaponCooldownTicks) {}
