package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Drools strategy rules.
 *
 * <p>Requires {@code @QuarkusTest} — {@link DroolsStrategyTask} uses {@code drools-quarkus}
 * whose {@code DataSource.createStore()} factory is initialised at Quarkus build time and is
 * unavailable in plain JUnit (see GE-0053). Tests call {@link StrategyTask#execute(io.casehub.core.CaseFile)}
 * directly with a populated {@link InMemoryCaseFile}.
 *
 * <p>Logic coverage for the same rules is also exercised by {@link BasicStrategyTaskTest}
 * (plain JUnit, no Quarkus boot) since both implementations share identical decisions.
 */
@QuarkusTest
class DroolsStrategyTaskTest {

    @Inject @CaseType("starcraft-game") StrategyTask strategyTask;
    @Inject IntentQueue intentQueue;

    @BeforeEach
    @AfterEach
    void drainQueue() {
        intentQueue.drainAll();
    }

    // --- Gateway ---

    @Test
    void buildsGatewayWhenPylonExistsAndMineralsAvailable() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus(), completePylon()), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithoutPylon() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus()), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayIfAlreadyExists() {
        var cf = caseFile(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    @Test
    void doesNotBuildGatewayWithInsufficientMinerals() {
        var cf = caseFile(100, 0, workers(6), List.of(nexus(), completePylon()), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY))
            .isTrue();
    }

    // --- CyberneticsCore ---

    @Test
    void buildsCyberneticsCoreWhenGatewayCompleteAndMineralsAvailable() {
        var cf = caseFile(200, 0, workers(6), List.of(nexus(), completePylon(), gateway(true)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCorIfGatewayNotComplete() {
        var cf = caseFile(300, 0, workers(6), List.of(nexus(), completePylon(), gateway(false)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    @Test
    void doesNotBuildCyberneticsCoreIfAlreadyExists() {
        var cf = caseFile(300, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(false)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.CYBERNETICS_CORE))
            .isTrue();
    }

    // --- Stalker training ---

    @Test
    void trainsStalkerWhenCoreAndGatewayCompleteAndGasAvailable() {
        var cf = caseFile(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .anyMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutGas() {
        var cf = caseFile(200, 0, workers(6),
            List.of(nexus(), completePylon(), gateway(true), cyberneticsCore(true)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    @Test
    void doesNotTrainStalkerWithoutCyberneticsCore() {
        var cf = caseFile(200, 100, workers(6),
            List.of(nexus(), completePylon(), gateway(true)), List.of());
        strategyTask.execute(cf);
        assertThat(intentQueue.pending().stream()
            .noneMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.STALKER))
            .isTrue();
    }

    // --- Strategy assessment ---

    @Test
    void strategyIsMacroWhenNoArmyAndNoEnemies() {
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), List.of());
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("MACRO");
    }

    @Test
    void strategyIsDefendWhenEnemiesVisible() {
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), List.of(enemyZealot()));
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("DEFEND");
    }

    @Test
    void strategyIsAttackWhenEnoughStalkers() {
        List<Unit> stalkers = IntStream.range(0, 4)
            .mapToObj(i -> new Unit("s-" + i, UnitType.STALKER, new Point2d(10, 10), 80, 80, 80, 80, 0))
            .toList();
        var cf = caseFile(50, 0, workers(12), List.of(nexus()), List.of());
        cf.put(QuarkMindCaseFile.ARMY, stalkers);
        strategyTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("ATTACK");
    }

    // --- Helpers ---

    private CaseFile caseFile(int minerals, int vespene,
                                     List<Unit> workers, List<Building> buildings,
                                     List<Unit> enemies) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.MINERALS,        minerals);
        cf.put(QuarkMindCaseFile.VESPENE,         vespene);
        cf.put(QuarkMindCaseFile.WORKERS,         workers);
        cf.put(QuarkMindCaseFile.ARMY,            List.of());
        cf.put(QuarkMindCaseFile.MY_BUILDINGS,    buildings);
        cf.put(QuarkMindCaseFile.ENEMY_UNITS,     enemies);
        cf.put(QuarkMindCaseFile.GEYSERS,         List.of());
        cf.put(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(minerals, vespene));
        cf.put(QuarkMindCaseFile.READY,           Boolean.TRUE);
        return cf;
    }

    private List<Unit> workers(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Unit("p-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0))
            .toList();
    }

    private Building nexus()           { return bldg("n-0",  BuildingType.NEXUS,            true);  }
    private Building completePylon()   { return bldg("py-0", BuildingType.PYLON,            true);  }
    private Building gateway(boolean c)      { return bldg("gw-0", BuildingType.GATEWAY,         c); }
    private Building cyberneticsCore(boolean c) { return bldg("cc-0", BuildingType.CYBERNETICS_CORE, c); }

    private Building bldg(String tag, BuildingType type, boolean complete) {
        return new Building(tag, type, new Point2d(10, 10), 500, 500, complete);
    }

    private Unit enemyZealot() {
        return new Unit("ez-0", UnitType.ZEALOT, new Point2d(20, 20), 100, 100, 50, 50, 0);
    }
}
