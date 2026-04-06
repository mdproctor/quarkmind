package org.acme.starcraft.agent;

public final class StarCraftCaseFile {
    // Observation state — written by GameStateTranslator
    public static final String MINERALS      = "game.resources.minerals";
    public static final String VESPENE       = "game.resources.vespene";
    public static final String SUPPLY_USED   = "game.resources.supply.used";
    public static final String SUPPLY_CAP    = "game.resources.supply.cap";
    public static final String WORKERS       = "game.units.workers";
    public static final String ARMY          = "game.units.army";
    public static final String ENEMY_UNITS   = "game.intel.enemy.units";
    public static final String GAME_FRAME    = "game.frame";
    public static final String READY         = "game.ready";

    // Agent state — written by plugins
    public static final String STRATEGY      = "agent.strategy.current";
    public static final String CRISIS        = "agent.intent.crisis";

    private StarCraftCaseFile() {}
}
