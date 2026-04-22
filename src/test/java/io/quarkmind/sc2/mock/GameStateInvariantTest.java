package io.quarkmind.sc2.mock;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generalised regression smoke tests for game state invariants.
 *
 * These run directly against SimulatedGame (no CDI, no browser) and verify
 * structural correctness after N ticks. Catches bugs like:
 * - Off-map tile positions (Pylon overflow)
 * - Duplicate units/buildings with the same tag
 * - Units floating at wrong Y positions (checked via world-coordinate bounds)
 * - Building/unit counts diverging from expected ranges
 *
 * Each test is a cheap JUnit check — no UI, no Playwright, no Quarkus boot.
 */
class GameStateInvariantTest {

    private static final float MAP_SIZE = 64f;
    private static final float TILE     = 0.7f;

    SimulatedGame game;

    @BeforeEach
    void setUp() {
        game = new SimulatedGame();
        game.reset();
    }

    // ── Position invariants ───────────────────────────────────────────────────

    @ParameterizedTest(name = "after {0} ticks — all unit positions within map")
    @ValueSource(ints = {0, 1, 10, 50})
    void allUnitPositionsWithinMapBoundsAfterNTicks(int ticks) {
        for (int i = 0; i < ticks; i++) game.tick();
        GameState state = game.snapshot();

        for (Unit u : state.myUnits()) {
            assertThat(u.position().x())
                .as("unit %s x after %d ticks", u.tag(), ticks)
                .isBetween(0f, MAP_SIZE);
            assertThat(u.position().y())
                .as("unit %s y after %d ticks", u.tag(), ticks)
                .isBetween(0f, MAP_SIZE);
        }
        for (Unit u : state.enemyUnits()) {
            assertThat(u.position().x())
                .as("enemy %s x after %d ticks", u.tag(), ticks)
                .isBetween(0f, MAP_SIZE);
            assertThat(u.position().y())
                .as("enemy %s y after %d ticks", u.tag(), ticks)
                .isBetween(0f, MAP_SIZE);
        }
    }

    @ParameterizedTest(name = "after {0} ticks — all building positions within map")
    @ValueSource(ints = {0, 1, 10, 50})
    void allBuildingPositionsWithinMapBoundsAfterNTicks(int ticks) {
        for (int i = 0; i < ticks; i++) game.tick();
        GameState state = game.snapshot();

        for (Building b : state.myBuildings()) {
            assertThat(b.position().x())
                .as("building %s (%s) x after %d ticks", b.tag(), b.type(), ticks)
                .isBetween(0f, MAP_SIZE);
            assertThat(b.position().y())
                .as("building %s (%s) y after %d ticks", b.tag(), b.type(), ticks)
                .isBetween(0f, MAP_SIZE);
        }
    }

    // ── Tag uniqueness invariants ─────────────────────────────────────────────

    @Test
    void unitTagsAreUniqueOnReset() {
        GameState state = game.snapshot();
        List<String> tags = state.myUnits().stream().map(Unit::tag).toList();
        assertThat(tags).doesNotHaveDuplicates();
    }

    @Test
    void buildingTagsAreUniqueOnReset() {
        GameState state = game.snapshot();
        List<String> tags = state.myBuildings().stream().map(Building::tag).toList();
        assertThat(tags).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "after {0} ticks — all tags unique")
    @ValueSource(ints = {1, 10, 50})
    void allTagsRemainUniqueAfterNTicks(int ticks) {
        for (int i = 0; i < ticks; i++) game.tick();
        GameState state = game.snapshot();

        List<String> unitTags = state.myUnits().stream().map(Unit::tag).toList();
        assertThat(unitTags).as("unit tags after %d ticks", ticks).doesNotHaveDuplicates();

        List<String> bldgTags = state.myBuildings().stream().map(Building::tag).toList();
        assertThat(bldgTags).as("building tags after %d ticks", ticks).doesNotHaveDuplicates();
    }

    // ── Count sanity invariants ───────────────────────────────────────────────

    @Test
    void initialStateHasExpectedCounts() {
        GameState state = game.snapshot();
        assertThat(state.myUnits()).as("initial probes").hasSize(12);
        assertThat(state.myBuildings()).as("initial buildings (Nexus only)").hasSize(1);
        assertThat(state.geysers()).as("initial geysers").hasSize(2);
        assertThat(state.enemyUnits()).as("no enemies at start").isEmpty();
    }

    @ParameterizedTest(name = "after {0} ticks — building count stays within expected range")
    @ValueSource(ints = {1, 10, 50})
    void buildingCountStaysReasonableAfterNTicks(int ticks) {
        for (int i = 0; i < ticks; i++) game.tick();
        GameState state = game.snapshot();
        // 1 initial (Nexus) + at most one Pylon per 18 ticks + some headroom
        int maxExpected = 1 + (ticks / 18) + 5;
        assertThat(state.myBuildings())
            .as("building count after %d ticks should be plausible (≤ %d)", ticks, maxExpected)
            .hasSizeLessThanOrEqualTo(maxExpected);
    }

    // ── Health invariants ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "after {0} ticks — no unit has health above its max")
    @ValueSource(ints = {0, 10, 50})
    void noUnitExceedsMaxHealth(int ticks) {
        for (int i = 0; i < ticks; i++) game.tick();
        GameState state = game.snapshot();
        for (Unit u : state.myUnits()) {
            assertThat(u.health())
                .as("unit %s health after %d ticks", u.tag(), ticks)
                .isLessThanOrEqualTo(u.maxHealth());
            assertThat(u.shields())
                .as("unit %s shields after %d ticks", u.tag(), ticks)
                .isLessThanOrEqualTo(u.maxShields());
        }
    }
}
