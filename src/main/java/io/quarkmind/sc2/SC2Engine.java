package io.quarkmind.sc2;

import io.quarkmind.domain.GameState;
import java.util.function.Consumer;

/**
 * Unified engine seam — the single CDI interface representing everything needed
 * to run one side of a StarCraft II game loop.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code MockEngine} — SimulatedGame-backed; clock owned by this interface</li>
 *   <li>{@code RealSC2Engine} — ocraft-s2client; clock owned by ocraft (tick is no-op)</li>
 * </ul>
 *
 * <p>Future implementations: {@code ReplayEngine}, {@code HttpSC2Engine}, or others.
 * See {@code docs/roadmap-sc2-engine.md}.
 * Existing implementations also include {@code EmulatedEngine}.
 */
public interface SC2Engine {

    // --- Lifecycle ---

    void connect();
    void joinGame();
    void leaveGame();
    boolean isConnected();

    // --- Per-tick operations (called by AgentOrchestrator) ---

    /** Advance internal clock by one agent tick. No-op when the engine owns its own clock (real SC2). */
    void tick();

    /** Return the current game state. */
    GameState observe();

    /** Flush pending intents to the game. No-op when the engine drains intents in its own callback (real SC2). */
    void dispatch();

    // --- Optional hooks ---

    /** Register a listener called on every {@link #observe()} invocation. Default: no-op. */
    default void addFrameListener(Consumer<GameState> listener) {}

    default String getMapName()   { return null; }
    default int    getMapWidth()  { return 0; }
    default int    getMapHeight() { return 0; }
}
