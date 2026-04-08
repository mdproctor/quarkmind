package io.quarkmind.plugin.scouting.events;

import io.quarkmind.domain.Point2d;

/**
 * Fired when an enemy unit is observed far from the estimated enemy main base.
 * Proxy for expansion detection — inserted at most once per map grid cell.
 * Inserted into ScoutingRuleUnit.expansionEvents DataStore.
 */
public record EnemyExpansionSeen(Point2d position, long gameTimeMs) {}
