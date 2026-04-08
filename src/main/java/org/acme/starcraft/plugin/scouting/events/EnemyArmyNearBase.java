package org.acme.starcraft.plugin.scouting.events;

/**
 * Fired when >= MIN_ARMY_SIZE enemy units are within NEAR_BASE_DISTANCE tiles of our Nexus.
 * Inserted into ScoutingRuleUnit.armyNearBaseEvents DataStore.
 * Java evicts events older than 10 seconds — so a non-empty DataStore means "active threat".
 */
public record EnemyArmyNearBase(int armySize, long gameTimeMs) {}
