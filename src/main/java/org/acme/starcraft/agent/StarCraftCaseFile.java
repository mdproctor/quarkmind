package org.acme.starcraft.agent;

public final class StarCraftCaseFile {
    // Observation state — written by GameStateTranslator
    public static final String MINERALS        = "game.resources.minerals";
    public static final String VESPENE         = "game.resources.vespene";
    public static final String SUPPLY_USED     = "game.resources.supply.used";
    public static final String SUPPLY_CAP      = "game.resources.supply.cap";
    public static final String WORKERS         = "game.units.workers";
    public static final String ARMY            = "game.units.army";
    public static final String MY_BUILDINGS    = "game.units.buildings";
    public static final String GEYSERS         = "game.resources.geysers";
    public static final String ENEMY_UNITS     = "game.intel.enemy.units";
    public static final String GAME_FRAME      = "game.frame";
    public static final String READY           = "game.ready";

    // Per-tick resource budget — written by GameStateTranslator, consumed by plugins
    public static final String RESOURCE_BUDGET = "agent.resources.budget";

    // Agent state — written by plugins
    public static final String STRATEGY        = "agent.strategy.current";
    public static final String CRISIS          = "agent.intent.crisis";
    public static final String ENEMY_ARMY_SIZE = "agent.intel.enemy.army.size";
    public static final String NEAREST_THREAT  = "agent.intel.enemy.nearest";

    private StarCraftCaseFile() {}
}
