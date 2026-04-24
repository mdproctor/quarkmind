package io.quarkmind.sc2.replay;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.replay.GameEventStream;
import io.quarkmind.sc2.replay.UnitOrder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Replay engine — drives the full agent loop from real {@code .SC2Replay} tracker events.
 * Active only in the {@code %replay} profile.
 *
 * <p>The engine is <em>observe-only</em>: {@link #dispatch()} drains and records the
 * agent's {@link io.quarkmind.sc2.intent.Intent}s but does not apply them to the
 * replay (the replay is immutable). This lets plugins run and produce decisions against
 * real game data for evaluation and offline analysis.
 *
 * <p>Configure via {@code application.properties}:
 * <pre>
 * %replay.starcraft.replay.file=replays/aiarena_protoss/Nothing_4720936.SC2Replay
 * %replay.starcraft.replay.player=1
 * </pre>
 *
 * <p>Run with: {@code mvn quarkus:dev -Dquarkus.profile=replay}
 */
@IfBuildProfile("replay")
@ApplicationScoped
public class ReplayEngine implements SC2Engine {

    private static final Logger log = Logger.getLogger(ReplayEngine.class);

    @ConfigProperty(name = "starcraft.replay.file")
    String replayFile;

    @ConfigProperty(name = "starcraft.replay.player", defaultValue = "1")
    int watchedPlayerId;

    @Inject
    IntentQueue intentQueue;

    private ReplaySimulatedGame game;
    private boolean connected = false;
    private final List<Consumer<GameState>> frameListeners = new CopyOnWriteArrayList<>();

    private String mapName;
    private int mapWidth;
    private int mapHeight;

    @Override
    public void connect() {
        log.infof("[REPLAY] Loading replay: %s (player %d)", replayFile, watchedPlayerId);
        game = new ReplaySimulatedGame(Path.of(replayFile), watchedPlayerId);
        parseMapMetadata(Path.of(replayFile));
        List<UnitOrder> orders = GameEventStream.parse(Path.of(replayFile));
        game.loadOrders(orders);
        log.infof("[REPLAY] Loaded %d movement orders from GAME_EVENTS", orders.size());
        connected = true;
        log.infof("[REPLAY] Replay loaded — %d tracker events ready, map=%s (%dx%d)",
                game.eventCount(), mapName, mapWidth, mapHeight);
    }

    private void parseMapMetadata(Path replayPath) {
        try (var mpq = new hu.belicza.andras.mpq.MpqParser(replayPath)) {
            byte[] meta = mpq.getFile("replay.gamemetadata.json");
            if (meta != null) {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(meta);
                String raw = node.path("MapName").asText(null);
                if (raw != null && raw.endsWith(".SC2Map")) {
                    mapName = raw.substring(0, raw.length() - 7);
                }
            }
            hu.scelight.sc2.rep.model.Replay r =
                hu.scelight.sc2.rep.factory.RepParserEngine.parseReplay(
                    replayPath,
                    java.util.EnumSet.of(hu.scelight.sc2.rep.factory.RepContent.INIT_DATA));
            if (r != null && r.initData != null) {
                var gd = r.initData.getGameDescription();
                mapWidth  = gd.getMapSizeX() != null ? gd.getMapSizeX() : 0;
                mapHeight = gd.getMapSizeY() != null ? gd.getMapSizeY() : 0;
            }
        } catch (Exception e) {
            log.warnf("[REPLAY] Cannot parse map metadata: %s", e.getMessage());
        }
    }

    @Override public String getMapName()   { return mapName; }
    @Override public int    getMapWidth()  { return mapWidth; }
    @Override public int    getMapHeight() { return mapHeight; }

    @Override
    public void joinGame() {
        game.reset();
        log.info("[REPLAY] Replay reset to loop 0");
    }

    @Override
    public void leaveGame() {
        connected = false;
        log.info("[REPLAY] Replay session ended");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void tick() {
        game.tick();
    }

    @Override
    public GameState observe() {
        if (game == null) return emptyState();
        GameState state = game.snapshot();
        frameListeners.forEach(l -> l.accept(state));
        return state;
    }

    private static GameState emptyState() {
        return new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
    }

    /**
     * Records what the agent decided — does not apply intents to the replay.
     * Intents are drained from the queue and logged for offline analysis.
     */
    @Override
    public void dispatch() {
        intentQueue.drainAll().forEach(intent ->
            log.debugf("[REPLAY] Agent intent (observe-only, not applied): %s", intent));
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }

    /** Returns true when the replay has no more events to process. */
    public boolean isReplayComplete() {
        return game.isComplete();
    }

    public long currentLoop() { return game != null ? game.currentLoop() : 0; }
    public long totalLoops()  { return game != null ? game.totalLoops()  : 0; }
    public void seekTo(long loop) { if (game != null) game.seekTo(loop); }
}
