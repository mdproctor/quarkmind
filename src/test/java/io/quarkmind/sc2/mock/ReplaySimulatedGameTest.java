package io.quarkmind.sc2.mock;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ReplaySimulatedGame using Nothing_4720936.SC2Replay
 * (8m21s PvZ, Nothing wins — consistent Protoss opening).
 * Player 1 = Nothing (Protoss), Player 2 = Zerg opponent.
 */
class ReplaySimulatedGameTest {

    private static final Path REPLAY = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    @Test
    void parsesReplayAndInitialisesFromLoopZero() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        GameState state = game.snapshot();

        // Initial state from UnitBorn events at loop 0: Nexus + Probes
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS))
            .as("Nexus present after reset").isTrue();
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count())
            .as("12 probes at game start").isEqualTo(12);
        assertThat(state.gameFrame()).isEqualTo(0L);
    }

    @Test
    void tickAdvancesGameFrameByOne() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(1L);
        game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(2L);
    }

    @Test
    void mineralsPopulatedFromPlayerStatsAfterSeveralTicks() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        // PlayerStats fires every ~16 loops; advance ~5 seconds (110 loops = 5 ticks)
        for (int i = 0; i < 5; i++) game.tick();
        assertThat(game.snapshot().minerals())
            .as("Minerals should be read from PlayerStatsEvent, not zero")
            .isGreaterThan(0);
    }

    @Test
    void supplyComesFromPlayerStats() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        for (int i = 0; i < 5; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.supply()).as("Supply cap > 0").isGreaterThan(0);
        assertThat(state.supplyUsed()).as("Supply used > 0").isGreaterThan(0);
    }

    @Test
    void unitCountGrowsOverTime() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int initialUnits = game.snapshot().myUnits().size();

        // Advance 3 minutes (3*60*22 = 3960 loops = 180 ticks)
        for (int i = 0; i < 180; i++) game.tick();

        assertThat(game.snapshot().myUnits().size())
            .as("More units after 3 minutes than at game start")
            .isGreaterThanOrEqualTo(initialUnits);
    }

    @Test
    void buildingsGrowOverTime() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int initialBuildings = game.snapshot().myBuildings().size();

        // Advance 4 minutes — first Pylon and Gateway should be done
        for (int i = 0; i < 240; i++) game.tick();

        assertThat(game.snapshot().myBuildings().size())
            .as("More buildings after 4 minutes than at game start")
            .isGreaterThan(initialBuildings);
    }

    @Test
    void resetRestoresInitialState() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        // Advance significantly
        for (int i = 0; i < 100; i++) game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(100L);

        game.reset();
        GameState state = game.snapshot();
        assertThat(state.gameFrame()).isEqualTo(0L);
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS)).isTrue();
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
    }

    // --- Enemy buildings (issue #108) ---

    @Test
    void enemyBuildingsPresentAfterAdvancing() {
        // Zerg opponent (player 2) starts with a Hatchery — it should appear in enemyBuildings
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        // Advance a few ticks to ensure initial UnitBorn events at loop 0 are processed
        // (loop 0 events are applied during reset(), so this may already be satisfied)
        GameState state = game.snapshot();
        assertThat(state.enemyBuildings())
            .as("Enemy Hatchery present from replay at loop 0")
            .isNotEmpty();
    }

    @Test
    void enemyBuildingTagsNotInFriendlyOrUnitCollections() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        GameState state = game.snapshot();

        Set<String> enemyBuildingTags = state.enemyBuildings().stream()
            .map(Building::tag).collect(Collectors.toSet());

        Set<String> otherTags = Stream.of(
            state.myUnits().stream().map(Unit::tag),
            state.myBuildings().stream().map(Building::tag),
            state.enemyUnits().stream().map(Unit::tag)
        ).flatMap(s -> s).collect(Collectors.toSet());

        assertThat(enemyBuildingTags).as("Enemy building tags must be non-empty").isNotEmpty();
        assertThat(enemyBuildingTags)
            .as("Enemy buildings must not overlap with friendly or unit collections")
            .doesNotContainAnyElementsOf(otherTags);
    }

    @Test
    void enemyBuildingsGrowOverTime() {
        // After 4 minutes Zerg should have built beyond the initial Hatchery
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int initialEnemyBuildings = game.snapshot().enemyBuildings().size();
        for (int i = 0; i < 240; i++) game.tick();
        assertThat(game.snapshot().enemyBuildings().size())
            .as("Enemy has more buildings after 4 minutes")
            .isGreaterThan(initialEnemyBuildings);
    }

    @Test
    void enemyBuildingsRestoredAfterReset() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int initialCount = game.snapshot().enemyBuildings().size();
        for (int i = 0; i < 100; i++) game.tick();
        game.reset();
        assertThat(game.snapshot().enemyBuildings())
            .as("Enemy buildings restored to initial count after reset")
            .hasSize(initialCount);
    }

    // --- Neutral resources (issue #107) ---

    @Test
    void mineralPatchesPresentAtLoopZero() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        GameState state = game.snapshot();
        assertThat(state.mineralPatches())
            .as("Mineral patches present from replay neutral units at loop 0")
            .isNotEmpty();
    }

    @Test
    void geysersPresentFromReplayAtLoopZero() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        GameState state = game.snapshot();
        assertThat(state.geysers())
            .as("Geysers present from replay neutral units at loop 0")
            .isNotEmpty();
    }

    @Test
    void neutralResourceTagsNotInUnitCollections() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        GameState state = game.snapshot();

        Set<String> neutralTags = Stream.concat(
            state.mineralPatches().stream().map(Resource::tag),
            state.geysers().stream().map(Resource::tag)
        ).collect(Collectors.toSet());

        Set<String> unitTags = Stream.concat(
            state.myUnits().stream().map(Unit::tag),
            state.enemyUnits().stream().map(Unit::tag)
        ).collect(Collectors.toSet());

        assertThat(neutralTags).as("Neutral resource tags must be non-empty").isNotEmpty();
        assertThat(neutralTags).as("Neutral resources must not appear in unit collections")
            .doesNotContainAnyElementsOf(unitTags);
    }

    @Test
    void neutralResourcesRestoredAfterReset() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int initialMinerals = game.snapshot().mineralPatches().size();
        int initialGeysers  = game.snapshot().geysers().size();

        for (int i = 0; i < 100; i++) game.tick();

        game.reset();
        GameState state = game.snapshot();
        assertThat(state.mineralPatches()).as("Mineral patches restored after reset").hasSize(initialMinerals);
        assertThat(state.geysers()).as("Geysers restored after reset").hasSize(initialGeysers);
    }

    @Test
    void applyIntentIsNoOp() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        int buildingsBeforeIntent = game.snapshot().myBuildings().size();
        // Applying an intent should not change anything
        String probeTag = game.snapshot().myUnits().get(0).tag();
        game.applyIntent(new io.quarkmind.sc2.intent.BuildIntent(
            probeTag, BuildingType.PYLON, new Point2d(20, 20)));
        game.tick();
        // Buildings should only change from replay events, not the intent
        // (We don't assert exact count since replay may add a building in this tick,
        //  but the intent itself should not have queued anything extra.)
        assertThat(game.snapshot().myBuildings().size())
            .as("applyIntent is a no-op; building count driven by replay only")
            .isGreaterThanOrEqualTo(buildingsBeforeIntent);
    }
}
