package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration harness for DroolsScoutingTask build-order thresholds.
 *
 * NOT part of the regular test suite. Run explicitly:
 *   mvn test -Pbenchmark
 *
 * Reads all AI Arena binary replays + all IEM10 JSON replays, ticks each to 3 min
 * (183 ticks × 22 loops), counts enemy units by type, and prints statistics.
 *
 * Read the output table and update DroolsScoutingTask.drl thresholds accordingly.
 * Output also written to target/scouting-calibration.txt.
 */
@Tag("benchmark")
class ScoutingCalibrationTest {

    private static final Path AI_ARENA_DIR = Path.of("replays/aiarena_protoss");
    private static final Path IEM10_ZIP    = Path.of("replays/2016_IEM_10_Taipei.zip");

    /** 3 minutes at SC2 Faster speed (22 loops/tick). */
    private static final int TICKS_3MIN = 183;

    @Test
    void calibrateScoutingThresholds() throws IOException {
        Map<String, List<Map<UnitType, Long>>> statsByMatchup = new LinkedHashMap<>();
        statsByMatchup.put("PvT", new ArrayList<>());
        statsByMatchup.put("PvZ", new ArrayList<>());
        statsByMatchup.put("PvP", new ArrayList<>());

        int aiArenaLoaded = 0, aiArenaSkipped = 0;
        int iem10Loaded   = 0;

        // ---- AI Arena binary replays (all PvP) ----
        List<Path> replayFiles = Files.list(AI_ARENA_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .collect(Collectors.toList());

        for (Path replay : replayFiles) {
            try {
                ReplaySimulatedGame game = new ReplaySimulatedGame(replay, 1);
                for (int i = 0; i < TICKS_3MIN; i++) game.tick();
                statsByMatchup.get("PvP").add(countByType(game.snapshot()));
                aiArenaLoaded++;
            } catch (IllegalArgumentException e) {
                aiArenaSkipped++; // unparseable build version
            }
        }

        // ---- IEM10 JSON replays (PvT, PvZ, PvP) ----
        List<IEM10JsonSimulatedGame> iem10Games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            for (int i = 0; i < TICKS_3MIN; i++) game.tick();
            statsByMatchup.get(game.matchup()).add(countByType(game.snapshot()));
            iem10Loaded++;
        }

        String report = buildReport(statsByMatchup, aiArenaLoaded, aiArenaSkipped, iem10Loaded);
        System.out.println(report);

        Path out = Path.of("target/scouting-calibration.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Written to: " + out.toAbsolutePath());

        assertThat(iem10Loaded).as("IEM10 games loaded").isGreaterThan(0);
        assertThat(aiArenaLoaded).as("AI Arena games loaded").isGreaterThan(0);
    }

    private static Map<UnitType, Long> countByType(GameState state) {
        Map<UnitType, Long> counts = new EnumMap<>(UnitType.class);
        for (var unit : state.enemyUnits()) {
            counts.merge(unit.type(), 1L, Long::sum);
        }
        return counts;
    }

    private static String buildReport(Map<String, List<Map<UnitType, Long>>> stats,
                                      int aiLoaded, int aiSkipped, int iem10Loaded) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Scouting CEP Calibration — enemy unit counts at 3-min mark ===\n");
        sb.append("AI Arena binary replays: ").append(aiLoaded).append(" loaded, ")
          .append(aiSkipped).append(" skipped (unparseable)\n");
        sb.append("IEM10 JSON replays: ").append(iem10Loaded).append(" loaded\n\n");

        sb.append(formatMatchup("PvZ (Zerg enemies)", stats.get("PvZ"),
            List.of(UnitType.ROACH, UnitType.ZERGLING, UnitType.HYDRALISK, UnitType.MUTALISK, UnitType.QUEEN),
            "ZERG_ROACH_RUSH threshold (current: 6) ← ROACH"));
        sb.append(formatMatchup("PvT (Terran enemies)", stats.get("PvT"),
            List.of(UnitType.MARINE, UnitType.MARAUDER, UnitType.MEDIVAC, UnitType.SIEGE_TANK),
            "TERRAN_3RAX threshold (current: 12) ← MARINE"));
        sb.append(formatMatchup("PvP (Protoss enemies)", stats.get("PvP"),
            List.of(UnitType.STALKER, UnitType.ZEALOT, UnitType.IMMORTAL, UnitType.ADEPT),
            "PROTOSS_4GATE threshold (current: 8) ← STALKER+ZEALOT combined"));

        return sb.toString();
    }

    private static String formatMatchup(String label, List<Map<UnitType, Long>> games,
                                        List<UnitType> types, String note) {
        if (games.isEmpty()) return label + ": no games\n\n";
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" (").append(games.size()).append(" games):\n");

        for (UnitType type : types) {
            long[] vals = games.stream()
                .mapToLong(m -> m.getOrDefault(type, 0L))
                .toArray();
            long min  = Arrays.stream(vals).min().orElse(0);
            long max  = Arrays.stream(vals).max().orElse(0);
            double mean = Arrays.stream(vals).average().orElse(0);
            sb.append(String.format("  %-12s min=%d  max=%d  mean=%.1f%n",
                type.name(), min, max, mean));
        }

        if (label.contains("PvP")) {
            long[] combined = games.stream()
                .mapToLong(m -> m.getOrDefault(UnitType.STALKER, 0L)
                              + m.getOrDefault(UnitType.ZEALOT,  0L))
                .toArray();
            long min  = Arrays.stream(combined).min().orElse(0);
            long max  = Arrays.stream(combined).max().orElse(0);
            double mean = Arrays.stream(combined).average().orElse(0);
            sb.append(String.format("  %-12s min=%d  max=%d  mean=%.1f%n",
                "combined", min, max, mean));
        }

        sb.append("  → ").append(note).append("\n\n");
        return sb.toString();
    }
}
