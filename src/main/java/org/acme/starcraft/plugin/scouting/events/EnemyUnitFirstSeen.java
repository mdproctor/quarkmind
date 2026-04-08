package org.acme.starcraft.plugin.scouting.events;

import org.acme.starcraft.domain.UnitType;

/**
 * Fired once per enemy unit tag — on first observation each game.
 * Proxy for "unit produced" given SC2 only provides observation snapshots.
 * Inserted into ScoutingRuleUnit.unitEvents DataStore.
 */
public record EnemyUnitFirstSeen(UnitType type, long gameTimeMs) {}
