package io.quarkmind.qa;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.SC2Engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies the WebSocket endpoint pushes valid GameState JSON
 * when the engine observes, and that the JSON structure matches what the PixiJS
 * visualizer expects (field names, entity arrays, unit-type serialisation).
 *
 * Uses SC2Engine.observe() directly rather than AgentOrchestrator.gameTick() to
 * avoid triggering the async economics Flow pipeline, which queues intents that
 * would pollute the IntentQueue across tests.
 *
 * Uses java.net.http.WebSocket (Java 11+) — no external client dependency.
 */
@QuarkusTest
class GameStateWebSocketTest {

    @TestHTTPResource("/ws/gamestate")
    URI wsUri;

    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;

    @BeforeEach
    void setUp() {
        orchestrator.startGame(); // connects + resets game state
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Connect to the WS endpoint and return a queue that receives each text message. */
    private WebSocket connect(LinkedBlockingQueue<String> queue) {
        URI ws = URI.create(wsUri.toString().replace("http://", "ws://"));
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(ws, new MessageCollector(queue))
            .join();
    }

    private static class MessageCollector implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final LinkedBlockingQueue<String> queue;

        MessageCollector(LinkedBlockingQueue<String> queue) { this.queue = queue; }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                queue.add(buf.toString());
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }
    }

    private static String poll(LinkedBlockingQueue<String> queue) throws InterruptedException {
        return queue.poll(3, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void webSocketReceivesJsonOnObserve() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe(); // fires frame listeners → GameStateBroadcaster → WebSocket push

        assertThat(poll(received))
            .as("WebSocket must receive a message within 3s of engine.observe()")
            .isNotNull();

        ws.abort();
    }

    @Test
    void jsonContainsAllTopLevelFields() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // Fields the PixiJS visualizer reads directly from the JSON message
        assertThat(json).contains("\"minerals\"");
        assertThat(json).contains("\"vespene\"");
        assertThat(json).contains("\"supply\"");
        assertThat(json).contains("\"supplyUsed\"");
        assertThat(json).contains("\"myUnits\"");
        assertThat(json).contains("\"myBuildings\"");
        assertThat(json).contains("\"enemyUnits\"");
        assertThat(json).contains("\"geysers\"");
        assertThat(json).contains("\"gameFrame\"");

        ws.abort();
    }

    @Test
    void jsonContainsProbeUnitsWithPositions() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // UNIT_ALIAS in visualizer.js maps "PROBE" → 'probe' sprite
        assertThat(json).contains("\"PROBE\"");

        // Position fields read as entity.position.x / entity.position.y in visualizer.js
        assertThat(json).contains("\"position\"");
        assertThat(json).contains("\"x\"");
        assertThat(json).contains("\"y\"");

        ws.abort();
    }

    @Test
    void jsonContainsNexusBuilding() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // BUILDING_ALIAS in visualizer.js maps "NEXUS" → 'nexus' sprite
        assertThat(json).contains("\"NEXUS\"");

        ws.abort();
    }

    @Test
    void mineralsIncreaseAfterMultipleTicks() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String first = poll(received);
        assertThat(first).isNotNull();

        // Advance game time — MockEngine adds +5 minerals per tick
        for (int i = 0; i < 5; i++) engine.tick();
        engine.observe();
        String later = poll(received);
        assertThat(later).isNotNull();

        int m1 = extractInt(first, "\"minerals\":");
        int m2 = extractInt(later, "\"minerals\":");
        assertThat(m2).as("minerals must increase over ticks").isGreaterThan(m1);

        ws.abort();
    }

    @Test
    void jsonContainsShieldsAndMaxShields() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // Every Unit in the JSON must carry shields and maxShields fields.
        // MockEngine's probes are Protoss — maxShields=20 for PROBE.
        assertThat(json).contains("\"shields\"");
        assertThat(json).contains("\"maxShields\"");
        assertThat(json).contains("\"maxShields\":20");

        ws.abort();
    }

    @Test
    void gameStateJsonContainsEnemyStagingArea() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();
        // Field must be present even when empty — visualizer reads it on every frame
        assertThat(json).contains("\"enemyStagingArea\"");

        ws.abort();
    }

    @Test
    void broadcastEnvelopeHasStateField() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // New envelope: {"state":{...},"visibility":...}
        assertThat(json).contains("\"state\"");
        // All game state fields are nested under "state"
        assertThat(json).contains("\"minerals\"");
        assertThat(json).contains("\"myUnits\"");

        ws.abort();
    }

    @Test
    void broadcastEnvelopeHasVisibilityField() throws Exception {
        var received = new LinkedBlockingQueue<String>(10);
        WebSocket ws = connect(received);
        ws.request(1);

        engine.observe();
        String json = poll(received);
        assertThat(json).isNotNull();

        // visibility field present (null in mock profile, string in emulated)
        assertThat(json).contains("\"visibility\"");

        ws.abort();
    }

    private static int extractInt(String json, String key) {
        int idx = json.indexOf(key);
        assertThat(idx).as("Key %s not found in JSON", key).isGreaterThanOrEqualTo(0);
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end));
    }
}
