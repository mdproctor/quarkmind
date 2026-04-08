package io.quarkmind.plugin.scouting.events;

import io.quarkmind.domain.UnitType;

/**
 * Fired once per enemy unit tag — on first observation each game.
 * Proxy for "unit produced" given SC2 only provides observation snapshots.
 * Inserted into ScoutingRuleUnit.unitEvents DataStore.
 */
public record EnemyUnitFirstSeen(UnitType type, long gameTimeMs) {}
