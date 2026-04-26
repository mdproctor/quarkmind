package io.quarkmind.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameStateBroadcasterTest {

    @Test
    void toJsonContainsExpectedFields() throws Exception {
        var broadcaster = new GameStateBroadcaster();
        broadcaster.objectMapper = new ObjectMapper();
        broadcaster.visibilityHolder = new io.quarkmind.sc2.emulated.VisibilityHolder();

        var state = new GameState(
            500, 25, 23, 14,
            List.of(new Unit("probe-0", UnitType.PROBE, new Point2d(9f, 9f), 45, 45, 20, 20, 0, 0)),
            List.of(new Building("nexus-0", BuildingType.NEXUS, new Point2d(8f, 8f), 1500, 1500, true)),
            List.of(),
            List.of(),   // enemyStagingArea
            List.of(new Resource("geyser-0", new Point2d(5f, 11f), 2250)),
            List.of(),   // mineralPatches
            42L
        );

        String json = broadcaster.toJson(state);

        assertThat(json).contains("\"minerals\":500");
        assertThat(json).contains("\"vespene\":25");
        assertThat(json).contains("\"myUnits\"");
        assertThat(json).contains("\"probe-0\"");
        assertThat(json).contains("\"gameFrame\":42");
        assertThat(json).contains("\"geysers\"");
        // Check isComplete serialises correctly (field name depends on Jackson version)
        assertThat(json).containsAnyOf("\"isComplete\":true", "\"complete\":true");
    }
}
