package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.drools.TacticsRuleUnit;
import io.quarkmind.plugin.tactics.GoapAction;
import io.quarkmind.plugin.tactics.GoapPlanner;
import io.quarkmind.plugin.tactics.WorldState;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Drools-backed GOAP {@link TacticsTask} — third R&D integration.
 *
 * <p>Each tick:
 * <ol>
 *   <li>DEFEND: bypasses GOAP — emits {@link MoveIntent} to Nexus for all units.</li>
 *   <li>ATTACK: fires {@link TacticsRuleUnit} to classify units into groups (Phase 1)
 *       and emit applicable action names (Phase 2).</li>
 *   <li>Java A* ({@link GoapPlanner}) finds the cheapest action sequence per group.</li>
 *   <li>First action in each plan is dispatched as an Intent.</li>
 * </ol>
 *
 * <p>Replaces {@link BasicTacticsTask} as the active CDI bean.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsTacticsTask implements TacticsTask {

    static final Point2d MAP_CENTER   = new Point2d(64, 64);
    static final double KITE_STEP = 1.0; // tiles per kite step

    private static final Map<String, GoapAction> ACTION_TEMPLATES = Map.of(
        "RETREAT",        new GoapAction("RETREAT",
            Map.of("lowHealth", true),
            Map.of("unitSafe", true), 1),
        "ATTACK",         new GoapAction("ATTACK",
            Map.of("inRange", true, "enemyVisible", true, "onCooldown", false),
            Map.of("enemyEliminated", true), 2),
        "MOVE_TO_ENGAGE", new GoapAction("MOVE_TO_ENGAGE",
            Map.of("enemyVisible", true, "inRange", false),
            Map.of("inRange", true), 1),
        "KITE",           new GoapAction("KITE",
            Map.of("inRange", true, "onCooldown", true, "enemyVisible", true),
            Map.of("onCooldown", false), 1)
    );

    private static final Logger log = Logger.getLogger(DroolsTacticsTask.class);

    private final RuleUnit<TacticsRuleUnit> ruleUnit;
    private final IntentQueue intentQueue;
    private final GoapPlanner planner = new GoapPlanner();

    @Inject
    public DroolsTacticsTask(RuleUnit<TacticsRuleUnit> ruleUnit, IntentQueue intentQueue) {
        this.ruleUnit    = ruleUnit;
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "tactics.drools-goap"; }
    @Override public String getName() { return "Drools GOAP Tactics"; }
    @Override public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        String strategy    = caseFile.get(QuarkMindCaseFile.STRATEGY,      String.class).orElse("MACRO");
        List<Unit> army    = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ARMY,         List.class).orElse(List.of());
        List<Unit> enemies = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());
        List<Building> bld = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        Point2d threat     = caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class).orElse(null);

        if (army.isEmpty()) return;

        if ("DEFEND".equals(strategy)) {
            dispatchDefend(army, bld);
            return;
        }

        if (!"ATTACK".equals(strategy)) return;

        if (enemies.isEmpty()) return;

        Set<String> inRangeSet    = computeInRangeTags(army, enemies);
        Set<String> onCooldownSet = computeOnCooldownTags(army);

        TacticsRuleUnit data = buildRuleUnit(army, enemies, inRangeSet, onCooldownSet, strategy);
        try (RuleUnitInstance<TacticsRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        Map<String, GroupInfo> groups = parseGroups(data.getGroupDecisions());

        if (groups.isEmpty()) return;

        List<GoapAction> allActions = List.copyOf(ACTION_TEMPLATES.values());

        Optional<Unit> focusTarget = selectFocusTarget(enemies);

        for (Map.Entry<String, GroupInfo> entry : groups.entrySet()) {
            String    groupId   = entry.getKey();
            GroupInfo groupInfo = entry.getValue();
            WorldState ws       = buildWorldState(groupId, !enemies.isEmpty());
            List<GoapAction> plan = planner.plan(ws, groupInfo.goalCondition(), allActions);
            if (!plan.isEmpty()) {
                dispatch(plan.get(0), groupInfo.unitTags(), army, enemies, threat, bld, focusTarget);
            }
            log.debugf("[DROOLS-GOAP] group=%s goal=%s plan=%s units=%d",
                groupId, groupInfo.goalCondition(),
                plan.stream().map(GoapAction::name).toList(),
                groupInfo.unitTags().size());
        }
    }

    static Set<String> computeInRangeTags(List<Unit> army, List<Unit> enemies) {
        Set<String> result = new HashSet<>();
        for (Unit unit : army) {
            for (Unit enemy : enemies) {
                if (distance(unit.position(), enemy.position()) <= SC2Data.attackRange(unit.type())) {
                    result.add(unit.tag());
                    break;
                }
            }
        }
        return result;
    }

    static double distance(Point2d a, Point2d b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    static Optional<Unit> selectFocusTarget(List<Unit> enemies) {
        return enemies.stream()
            .min(Comparator.comparingInt(e -> e.health() + e.shields()));
    }

    static Set<String> computeOnCooldownTags(List<Unit> army) {
        return army.stream()
            .filter(u -> u.weaponCooldownTicks() > 0)
            .map(Unit::tag)
            .collect(Collectors.toSet());
    }

    static Point2d kiteRetreatTarget(Point2d unitPos, List<Unit> enemies) {
        Unit nearest = enemies.stream()
            .min(Comparator.comparingDouble(e -> distance(unitPos, e.position())))
            .orElseThrow();
        double dx = unitPos.x() - nearest.position().x();
        double dy = unitPos.y() - nearest.position().y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) return unitPos; // degenerate: unit on top of enemy
        return new Point2d(
            (float)(unitPos.x() + dx / len * KITE_STEP),
            (float)(unitPos.y() + dy / len * KITE_STEP));
    }

    private TacticsRuleUnit buildRuleUnit(List<Unit> army, List<Unit> enemies,
                                           Set<String> inRangeTags, Set<String> onCooldownTags,
                                           String strategy) {
        TacticsRuleUnit data = new TacticsRuleUnit();
        data.setStrategyGoal(strategy);
        army.forEach(data.getArmy()::add);
        enemies.forEach(data.getEnemies()::add);
        inRangeTags.forEach(data.getInRangeTags()::add);
        onCooldownTags.forEach(data.getOnCooldownTags()::add);
        return data;
    }

    private Map<String, GroupInfo> parseGroups(List<String> groupDecisions) {
        Map<String, GroupInfo> groups = new LinkedHashMap<>();
        for (String decision : groupDecisions) {
            String[] parts = decision.split(":", 3);
            if (parts.length < 3) {
                log.warnf("[DROOLS-GOAP] Malformed group decision ignored: %s", decision);
                continue;
            }
            String groupId = parts[0];
            String goalKey = goalConditionKey(parts[1]);
            String unitTag = parts[2];
            groups.computeIfAbsent(groupId, k -> new GroupInfo(goalKey, new ArrayList<>()))
                  .unitTags().add(unitTag);
        }
        return groups;
    }

    private String goalConditionKey(String goalName) {
        return switch (goalName) {
            case "UNIT_SAFE"        -> "unitSafe";
            case "ENEMY_ELIMINATED" -> "enemyEliminated";
            case "KITING"           -> "enemyEliminated"; // plan: KITE → ATTACK
            default                 -> goalName.toLowerCase();
        };
    }

    private WorldState buildWorldState(String groupId, boolean enemyVisible) {
        return switch (groupId) {
            case "low-health"   -> new WorldState(Map.of(
                "lowHealth",       true,
                "enemyVisible",    enemyVisible,
                "inRange",         false,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "in-range"     -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         true,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "out-of-range" -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         false,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "kiting" -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         true,
                "onCooldown",      true,
                "unitSafe",        false,
                "enemyEliminated", false));
            default             -> new WorldState(Map.of("enemyEliminated", false));
        };
    }

    private void dispatch(GoapAction action, List<String> unitTags,
                          List<Unit> army, List<Unit> enemies,
                          Point2d threat, List<Building> buildings,
                          Optional<Unit> focusTarget) {
        switch (action.name()) {
            case "ATTACK" -> {
                Point2d target = focusTarget
                    .map(Unit::position)
                    .orElse(threat != null ? threat : MAP_CENTER);
                unitTags.forEach(tag -> intentQueue.add(new AttackIntent(tag, target)));
            }
            case "MOVE_TO_ENGAGE" -> {
                Point2d target = threat != null ? threat : MAP_CENTER;
                unitTags.forEach(tag -> intentQueue.add(new MoveIntent(tag, target)));
            }
            case "RETREAT" -> {
                Point2d rally = buildings.stream()
                    .filter(b -> b.type() == BuildingType.NEXUS)
                    .findFirst()
                    .map(Building::position)
                    .orElse(MAP_CENTER);
                unitTags.forEach(tag -> intentQueue.add(new MoveIntent(tag, rally)));
            }
            case "KITE" -> {
                unitTags.forEach(tag ->
                    army.stream()
                        .filter(u -> u.tag().equals(tag))
                        .findFirst()
                        .ifPresent(unit -> {
                            Point2d retreatTarget = kiteRetreatTarget(unit.position(), enemies);
                            intentQueue.add(new MoveIntent(tag, retreatTarget));
                        })
                );
            }
        }
    }

    private void dispatchDefend(List<Unit> army, List<Building> buildings) {
        Point2d rally = buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(MAP_CENTER);
        army.forEach(unit -> intentQueue.add(new MoveIntent(unit.tag(), rally)));
    }

    private record GroupInfo(String goalCondition, List<String> unitTags) {}
}
