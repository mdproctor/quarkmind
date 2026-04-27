package io.quarkmind.sc2.mock;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario validation tests: run real .SC2Replay files through ReplaySimulatedGame
 * at full speed and assert invariants at regular intervals plus known states at
 * specific moments. Catches regressions in tracker event parsing, unit tracking,
 * and resource handling.
 *
 * Also measures raw replay throughput (loops/sec) as a performance baseline.
 *
 * All tests are plain JUnit — no CDI, no Quarkus boot cost.
 */
class ReplayScenarioValidationTest {

    private static final int LOOPS_PER_SEC = 22; // Faster speed ≈ 22 loops/sec
    private static final int CHECK_INTERVAL = LOOPS_PER_SEC * 30; // assert every 30 game-seconds

    // ------------------------------------------------------------------
    // Replay paths used in multiple tests
    // ------------------------------------------------------------------
    private static final Path NOTHING_PVZ =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
    private static final Path NOTHING_PVZ_2 =
        Path.of("replays/aiarena_protoss/Nothing_4720938.SC2Replay");
    private static final Path NOTHING_PVZ_3 =
        Path.of("replays/aiarena_protoss/Nothing_4720939.SC2Replay");
    private static final Path PUCK_PVZ_SHORT =
        Path.of("replays/aiarena_protoss/puck_4720480.SC2Replay");
    private static final Path ARGOBOT_PVT =
        Path.of("replays/aiarena_protoss/ArgoBot_4721229.SC2Replay");
    private static final Path TYCKLES_PVP_LONG =
        Path.of("replays/aiarena_protoss/Tyckles_4721036.SC2Replay");
    private static final Path STARLIGHT_PVZ_LONG =
        Path.of("replays/aiarena_protoss/Starlight_4721166.SC2Replay");

    // ------------------------------------------------------------------
    // Known ground-truth states for Nothing_4720936 (PvZ, ~8m21s)
    // Verified against /qa/replay/snapshot with the running Electron app.
    // ------------------------------------------------------------------

    @Test
    void nothingPvZ_initialStateIsCanonical() {
        var game = new ReplaySimulatedGame(NOTHING_PVZ, 1);
        var state = game.snapshot();

        // Protoss start
        assertThat(unitsByType(state.myUnits()).get("PROBE"))
            .as("12 probes at game start").isEqualTo(12L);
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS))
            .as("Nexus present").isTrue();
        assertThat(state.myBuildings()).as("only Nexus at start").hasSize(1);

        // Zerg start
        var enemyUnits = unitsByType(state.enemyUnits());
        assertThat(enemyUnits.get("DRONE")).as("12 drones").isEqualTo(12L);
        assertThat(enemyUnits.get("OVERLORD")).as("1 overlord").isEqualTo(1L);
        assertThat(state.enemyBuildings()).as("1 Hatchery").hasSize(1);
        assertThat(state.enemyBuildings().get(0).type()).isEqualTo(BuildingType.HATCHERY);

        // Resources
        assertThat(state.mineralPatches()).as("mineral patches from map").isNotEmpty();
        assertThat(state.geysers()).as("geysers from map").isNotEmpty();
        // Minerals come from PlayerStatsEvent which fires ~16 loops after start, not at loop 0
        assertThat(state.gameFrame()).isEqualTo(0L);
    }

    @Test
    void nothingPvZ_zergBuildOrderSequenceIsValid() {
        var game = new ReplaySimulatedGame(NOTHING_PVZ, 1);

        // Advance to 2 min — SpawningPool should exist by then in any Zerg game
        tickTo(game, LOOPS_PER_SEC * 60 * 2);
        var state2m = game.snapshot();
        assertThat(buildingsByType(state2m.enemyBuildings()).keySet())
            .as("Zerg has SpawningPool by 2 min")
            .contains("SPAWNING_POOL");

        // Advance to 4 min — if Zerglings exist, SpawningPool must exist
        tickTo(game, LOOPS_PER_SEC * 60 * 4);
        var state4m = game.snapshot();
        if (state4m.enemyUnits().stream().anyMatch(u -> u.type() == UnitType.ZERGLING)) {
            assertThat(buildingsByType(state4m.enemyBuildings()).keySet())
                .as("Zerglings require SpawningPool")
                .contains("SPAWNING_POOL");
        }
    }

    // ------------------------------------------------------------------
    // Universal invariants — run across multiple replays
    // ------------------------------------------------------------------

    static Stream<Arguments> allParsedReplays() {
        return Stream.of(
            Arguments.of("NothingPvZ-1",      NOTHING_PVZ,        1),
            Arguments.of("NothingPvZ-2",      NOTHING_PVZ_2,      1),
            Arguments.of("NothingPvZ-3",      NOTHING_PVZ_3,      1),
            Arguments.of("PuckPvZ-short",     PUCK_PVZ_SHORT,     1),
            Arguments.of("ArgoBotPvT",        ARGOBOT_PVT,        1),
            Arguments.of("StarLightPvZ-long", STARLIGHT_PVZ_LONG, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allParsedReplays")
    void invariantsHoldThroughoutReplay(String label, Path replayPath, int watchedPlayer) {
        var game = new ReplaySimulatedGame(replayPath, watchedPlayer);

        int initialMinerals = game.snapshot().mineralPatches().size();
        int initialGeysers  = game.snapshot().geysers().size();
        long checkedAt = 0;

        while (!game.isComplete()) {
            for (int i = 0; i < CHECK_INTERVAL && !game.isComplete(); i++) game.tick();
            var s = game.snapshot();

            // Supply: never over-cap
            assertThat(s.supplyUsed())
                .as("[%s] supplyUsed ≤ supply at loop %d", label, game.currentLoop())
                .isLessThanOrEqualTo(s.supply() + 2); // +2 tolerance for race-dependent supply

            // Minerals: only deplete, never appear
            assertThat(s.mineralPatches().size())
                .as("[%s] mineralPatches never exceed initial count at loop %d", label, game.currentLoop())
                .isLessThanOrEqualTo(initialMinerals);

            // Geysers: stable throughout (not depleted in tracker events)
            assertThat(s.geysers().size())
                .as("[%s] geyser count stable at loop %d", label, game.currentLoop())
                .isEqualTo(initialGeysers);

            // Friendly units: no duplicate tags
            var myTags = s.myUnits().stream().map(Unit::tag).collect(Collectors.toList());
            assertThat(myTags)
                .as("[%s] no duplicate friendly unit tags at loop %d", label, game.currentLoop())
                .doesNotHaveDuplicates();

            // Enemy units: no duplicate tags
            var enemyTags = s.enemyUnits().stream().map(Unit::tag).collect(Collectors.toList());
            assertThat(enemyTags)
                .as("[%s] no duplicate enemy unit tags at loop %d", label, game.currentLoop())
                .doesNotHaveDuplicates();

            // Game frame advances monotonically
            assertThat(s.gameFrame())
                .as("[%s] game frame increases at loop %d", label, game.currentLoop())
                .isGreaterThan(checkedAt);
            checkedAt = s.gameFrame();
        }
    }

    // ------------------------------------------------------------------
    // Throughput benchmark — measures raw replay processing speed.
    // Not a correctness test: asserts no minimum (machines vary).
    // Results printed for manual comparison and stored in git log.
    // Run with: mvn test -Dtest=ReplayScenarioValidationTest#replayThroughput*
    // ------------------------------------------------------------------

    static Stream<Arguments> benchmarkReplays() {
        return Stream.of(
            Arguments.of("NothingPvZ (~8m)",      NOTHING_PVZ),
            Arguments.of("PuckPvZ-short (~4m)",   PUCK_PVZ_SHORT),
            Arguments.of("ArgoBotPvT (~10m)",     ARGOBOT_PVT),
            Arguments.of("StarLightPvZ-long (~20m)", STARLIGHT_PVZ_LONG),
            Arguments.of("TycklesPvP-marathon (~44m)", TYCKLES_PVP_LONG)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("benchmarkReplays")
    @Tag("benchmark")
    void replayThroughputBenchmark(String label, Path replayPath) {
        var game = new ReplaySimulatedGame(replayPath, 1);
        long totalLoops = game.totalLoops();

        long start = System.nanoTime();
        while (!game.isComplete()) game.tick();
        long elapsedNs = System.nanoTime() - start;

        double elapsedMs  = elapsedNs / 1_000_000.0;
        double loopsPerSec = totalLoops / (elapsedNs / 1e9);
        double realTimeMult = loopsPerSec / 22.4; // 22.4 loops/sec = 1× real-time

        System.out.printf(
            "%-36s | loops=%6d | time=%6.1f ms | %8.0f loops/sec | %.0fx real-time%n",
            label, totalLoops, elapsedMs, loopsPerSec, realTimeMult);

        // Sanity: replay must complete (no infinite loop)
        assertThat(game.isComplete()).isTrue();
        // Minimum correctness: something was tracked
        assertThat(game.snapshot().gameFrame()).isGreaterThan(0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void tickTo(ReplaySimulatedGame game, long targetLoop) {
        while (game.currentLoop() < targetLoop && !game.isComplete()) game.tick();
    }

    private static Map<String, Long> unitsByType(List<Unit> units) {
        return units.stream().collect(
            Collectors.groupingBy(u -> u.type().name(), Collectors.counting()));
    }

    private static Map<String, Long> buildingsByType(List<Building> buildings) {
        return buildings.stream().collect(
            Collectors.groupingBy(b -> b.type().name(), Collectors.counting()));
    }
}
