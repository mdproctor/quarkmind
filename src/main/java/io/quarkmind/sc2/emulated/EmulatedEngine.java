package io.quarkmind.sc2.emulated;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.qa.EmulatedConfig;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SC2Engine implementation backed by {@link EmulatedGame}.
 * Active only in the {@code %emulated} profile.
 * Mirrors {@link io.quarkmind.sc2.mock.MockEngine} in structure.
 */
@IfBuildProfile("emulated")
@ApplicationScoped
public class EmulatedEngine implements SC2Engine {

    private static final Logger log = Logger.getLogger(EmulatedEngine.class);

    private final EmulatedGame game = new EmulatedGame();
    private final IntentQueue intentQueue;
    private final EmulatedConfig config;
    private final VisibilityHolder visibilityHolder;
    private final List<Consumer<GameState>> frameListeners = new CopyOnWriteArrayList<>();
    private boolean connected = false;

    @Inject
    public EmulatedEngine(IntentQueue intentQueue, EmulatedConfig config,
                          VisibilityHolder visibilityHolder) {
        this.intentQueue       = intentQueue;
        this.config            = config;
        this.visibilityHolder  = visibilityHolder;
    }

    @Override
    public void connect() {
        connected = true;
        log.info("[EMULATED] Engine connected");
    }

    @Override
    public void joinGame() {
        // Apply wave config from EmulatedConfig before reset() so pendingWaves is populated
        game.configureWave(
            config.getWaveSpawnFrame(),
            config.getWaveUnitCount(),
            UnitType.valueOf(config.getWaveUnitType()));
        // Wire pathfinding and the independent physics wall constraint
        TerrainGrid grid = TerrainGrid.emulatedMap();
        game.setMovementStrategy(new PathfindingMovement(grid));
        game.setTerrainGrid(grid);
        log.info("[EMULATED] PathfindingMovement wired — wall y=18, gap x=11-13");
        log.info("[EMULATED] Wall physics constraint active — no unit may land on a wall tile");
        game.reset();
        log.infof("[EMULATED] Joined game — wave at frame %d (%dx%s), speed=%.2f",
            config.getWaveSpawnFrame(), config.getWaveUnitCount(),
            config.getWaveUnitType(), config.getUnitSpeed());
    }

    @Override
    public void leaveGame() {
        connected = false;
        log.info("[EMULATED] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void tick() {
        game.setUnitSpeed(config.getUnitSpeed());           // existing
        game.setEnemyStrategy(config.getEnemyStrategy());   // new E4: live strategy updates
        game.tick();
        visibilityHolder.set(game.observeVisibility());
    }

    @Override
    public GameState observe() {
        GameState state = game.snapshot();
        frameListeners.forEach(l -> l.accept(state));
        return state;
    }

    @Override
    public void dispatch() {
        intentQueue.drainAll().forEach(intent -> {
            log.debugf("[EMULATED] Dispatching: %s", intent);
            game.applyIntent(intent);
        });
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }
}
