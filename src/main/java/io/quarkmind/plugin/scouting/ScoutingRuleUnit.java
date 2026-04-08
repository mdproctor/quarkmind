package io.quarkmind.plugin.scouting;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;
import io.quarkmind.plugin.scouting.events.EnemyArmyNearBase;
import io.quarkmind.plugin.scouting.events.EnemyExpansionSeen;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Unit data context for Drools scouting evaluation.
 *
 * <p>Inputs (populated from Java-side event buffers each tick):
 * <ul>
 *   <li>{@link #unitEvents} — enemy units first seen within the 3-min build-order window</li>
 *   <li>{@link #expansionEvents} — permanent: enemy-area unit sightings (expansion proxy)</li>
 *   <li>{@link #armyNearBaseEvents} — enemy army near our Nexus within the 10-sec window</li>
 * </ul>
 *
 * <p>Outputs (written by rules, read by DroolsScoutingTask after fire()):
 * <ul>
 *   <li>{@link #detectedBuilds} — detected build-order strings, e.g. "ZERG_ROACH_RUSH"</li>
 *   <li>{@link #timingAlerts} — non-empty = timing attack detected this tick</li>
 *   <li>{@link #postureDecisions} — "ALL_IN", "MACRO", or empty (= "UNKNOWN")</li>
 * </ul>
 */
public class ScoutingRuleUnit implements RuleUnitData {

    private final DataStore<EnemyUnitFirstSeen>  unitEvents         = DataSource.createStore();
    private final DataStore<EnemyExpansionSeen>  expansionEvents    = DataSource.createStore();
    private final DataStore<EnemyArmyNearBase>   armyNearBaseEvents = DataSource.createStore();

    private final List<String>  detectedBuilds   = new ArrayList<>();
    private final List<Boolean> timingAlerts     = new ArrayList<>();
    private final List<String>  postureDecisions = new ArrayList<>();

    public DataStore<EnemyUnitFirstSeen>  getUnitEvents()         { return unitEvents; }
    public DataStore<EnemyExpansionSeen>  getExpansionEvents()    { return expansionEvents; }
    public DataStore<EnemyArmyNearBase>   getArmyNearBaseEvents() { return armyNearBaseEvents; }

    public List<String>  getDetectedBuilds()   { return detectedBuilds; }
    public List<Boolean> getTimingAlerts()     { return timingAlerts; }
    public List<String>  getPostureDecisions() { return postureDecisions; }
}
