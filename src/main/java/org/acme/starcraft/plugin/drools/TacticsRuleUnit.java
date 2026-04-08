package org.acme.starcraft.plugin.drools;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;
import org.acme.starcraft.domain.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Unit data context for Drools tactics evaluation.
 *
 * <p>Per GE-0053: only JDK types and {@link DataStore}{@code <T>} (generic type erased)
 * may appear as plain field types. Application types must use DataStore wrapping.
 *
 * <p>Phase 1 rules (salience 200+) classify units into groups, writing to
 * {@link #groupDecisions} and inserting group IDs into {@link #activeGroups}.
 *
 * <p>Phase 2 rules (salience 100+) match on {@link #activeGroups} and emit
 * applicable action names to {@link #actionDecisions}.
 */
public class TacticsRuleUnit implements RuleUnitData {

    private final DataStore<Unit>   army           = DataSource.createStore();
    private final DataStore<Unit>   enemies        = DataSource.createStore();
    private final DataStore<String> inRangeTags    = DataSource.createStore();
    private final DataStore<String> activeGroups   = DataSource.createStore();
    private final List<String>      groupDecisions  = new ArrayList<>();
    private final List<String>      actionDecisions = new ArrayList<>();
    private String strategyGoal = "MACRO";

    public DataStore<Unit>   getArmy()             { return army; }
    public DataStore<Unit>   getEnemies()           { return enemies; }
    public DataStore<String> getInRangeTags()       { return inRangeTags; }
    public DataStore<String> getActiveGroups()      { return activeGroups; }
    public List<String>      getGroupDecisions()    { return groupDecisions; }
    public List<String>      getActionDecisions()   { return actionDecisions; }
    public String            getStrategyGoal()      { return strategyGoal; }
    public void              setStrategyGoal(String g) { this.strategyGoal = g; }
}
