package io.quarkmind.agent;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Game loop smoke benchmark — measures per-phase tick timings across the full plugin chain.
 *
 * NOT part of the regular test suite. Run explicitly when investigating performance:
 *
 *   mvn test -Dgroups=benchmark
 *
 * Output is printed to stdout AND written to target/benchmark-results.txt.
 * Paste the table into docs/benchmarks/YYYY-MM-DD-<context>.md to record a snapshot.
 *
 * When to run (see CLAUDE.md):
 *   - Before/after adding or modifying a plugin
 *   - Before/after dependency upgrades (Drools, Quarkus Flow, CaseHub)
 *   - After any change to AgentOrchestrator.gameTick() or caseEngine timeout
 *   - After any change to EmulatedGame.tick() physics
 *   - Whenever a tick feels sluggish in the visualizer
 */
@QuarkusTest
@Tag("benchmark")
class GameLoopBenchmarkTest {

    private static final int WARMUP_TICKS  = 5;
    private static final int MEASURE_TICKS = 30;

    // Generous guards — flag catastrophic regressions, not tight budgets
    private static final long GUARD_MEAN_MS  = 5_000;
    private static final long GUARD_MAX_MS   = 10_000;

    @Inject AgentOrchestrator orchestrator;

    @Test
    void gameLoopSmokeTimings() throws IOException {
        orchestrator.startGame();

        // Warmup — discard timings
        for (int i = 0; i < WARMUP_TICKS; i++) {
            orchestrator.gameTick();
        }

        // Measure
        long[] physicsMs  = new long[MEASURE_TICKS];
        long[] pluginsMs  = new long[MEASURE_TICKS];
        long[] dispatchMs = new long[MEASURE_TICKS];
        long[] totalMs    = new long[MEASURE_TICKS];

        for (int i = 0; i < MEASURE_TICKS; i++) {
            orchestrator.gameTick();
            AgentOrchestrator.TickTimings t = orchestrator.getLastTickTimings();
            physicsMs [i] = t.physicsMs();
            pluginsMs [i] = t.pluginsMs();
            dispatchMs[i] = t.dispatchMs();
            totalMs   [i] = t.totalMs();
        }

        String report = formatReport(physicsMs, pluginsMs, dispatchMs, totalMs);
        System.out.println(report);

        // Write to file for easy copy-paste into docs/benchmarks/
        Path out = Path.of("target/benchmark-results.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Written to: " + out.toAbsolutePath());

        // Soft guards — must not be catastrophically slow
        long meanTotal = mean(totalMs);
        long maxTotal  = max(totalMs);
        assertThat(meanTotal).as("Mean tick time guard").isLessThan(GUARD_MEAN_MS);
        assertThat(maxTotal) .as("Max tick time guard") .isLessThan(GUARD_MAX_MS);
    }

    private static String formatReport(long[] physics, long[] plugins,
                                        long[] dispatch, long[] total) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return String.format("""
                QuarkMind Game Loop Benchmark
                ──────────────────────────────────────────────────────
                Date:    %s
                Profile: %%test (MockEngine)   Warmup: %d   Samples: %d
                ──────────────────────────────────────────────────────
                Phase               mean     p95      max
                engine.tick()      %4dms   %4dms   %4dms
                engine.observe()   (included in physics above)
                caseEngine plugins %4dms   %4dms   %4dms
                engine.dispatch()  %4dms   %4dms   %4dms
                ────────────────────────────────────────
                Total gameTick()   %4dms   %4dms   %4dms
                ──────────────────────────────────────────────────────
                Raw total samples (ms): %s
                ──────────────────────────────────────────────────────
                Paste this table into docs/benchmarks/YYYY-MM-DD-<context>.md to track over time.
                Run with: mvn test -Pbenchmark
                """,
            ts,
            WARMUP_TICKS, MEASURE_TICKS,
            mean(physics),   p95(physics),   max(physics),
            mean(plugins),   p95(plugins),   max(plugins),
            mean(dispatch),  p95(dispatch),  max(dispatch),
            mean(total),     p95(total),     max(total),
            Arrays.toString(total));
    }

    private static long mean(long[] a) {
        return LongStream.of(a).sum() / a.length;
    }

    private static long p95(long[] a) {
        long[] sorted = Arrays.stream(a).sorted().toArray();
        return sorted[Math.min((int)(a.length * 0.95), a.length - 1)];
    }

    private static long max(long[] a) {
        return LongStream.of(a).max().orElse(0);
    }
}
