package io.quarkmind.plugin.drools;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Unit data context for Drools strategy evaluation.
 *
 * <p>Only JDK and Drools-known types appear as non-{@link DataStore} fields. Application
 * domain classes ({@code ResourceBudget}, {@code IntentQueue}) must NOT appear as plain
 * field types here — {@code drools-ruleunits-impl}'s {@code SimpleRuleUnitVariable}
 * calls {@code Class.forName()} on each field type during static initialisation of the
 * generated CDI bean, and application classes may not be visible in the Drools classloader
 * at that point (see GE-0053).
 *
 * <p>DataStore generic type parameters are erased at runtime, so {@code DataStore<Unit>}
 * etc. are safe — only the raw type {@code DataStore} is loaded.
 *
 * <p><b>Architecture:</b> rules are declarative — they add string decisions to
 * {@link #buildDecisions} and {@link #strategyDecisions}. Budget enforcement and intent
 * dispatch are handled by {@link io.quarkmind.plugin.DroolsStrategyTask}.
 */
public class StrategyRuleUnit implements RuleUnitData {

    /** Designated builder probe — 0 or 1 items (pre-selected per tick). */
    private final DataStore<Unit>     builders  = DataSource.createStore();

    /** All player buildings (complete and in-progress). */
    private final DataStore<Building> buildings = DataSource.createStore();

    /** All army units (non-probe). */
    private final DataStore<Unit>     army      = DataSource.createStore();

    /** Visible enemy units. */
    private final DataStore<Unit>     enemies   = DataSource.createStore();

    /**
     * First unoccupied vespene geyser — 0 or 1 items.
     * Pre-filtered by {@link io.quarkmind.plugin.DroolsStrategyTask}.
     */
    private final DataStore<Resource> geysers   = DataSource.createStore();

    /**
     * Build decisions written by rules. Strings from the set
     * {@code "GATEWAY", "ASSIMILATOR", "CYBERNETICS_CORE", "STALKER:<gatewayTag>"}.
     * Java handles budget enforcement and intent dispatch after fire().
     */
    private final List<String> buildDecisions = new ArrayList<>();

    /**
     * Strategic posture written by rules: {@code "DEFEND"} or {@code "ATTACK"}.
     * Empty = default {@code "MACRO"}.
     */
    private final List<String> strategyDecisions = new ArrayList<>();

    public DataStore<Unit>     getBuilders()         { return builders; }
    public DataStore<Building> getBuildings()        { return buildings; }
    public DataStore<Unit>     getArmy()             { return army; }
    public DataStore<Unit>     getEnemies()          { return enemies; }
    public DataStore<Resource> getGeysers()          { return geysers; }
    public List<String>        getBuildDecisions()   { return buildDecisions; }
    public List<String>        getStrategyDecisions(){ return strategyDecisions; }
}
