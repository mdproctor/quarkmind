package org.acme.starcraft.sc2.real;

import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.ScenarioRunner;
import org.jboss.logging.Logger;
import java.util.Set;

/**
 * Triggers named scenarios against a live SC2 game via the SC2 Debug API.
 * Same scenario names as MockScenarioRunner — integration parity by design.
 *
 * IMPORTANT — ocraft debug API constraint:
 *   debug() and sendDebug() must be called from within the onStep() callback.
 *   This class enqueues Runnable commands on SC2BotAgent.pendingDebugCommands;
 *   SC2BotAgent drains them and calls sendDebug() each frame.
 *
 * Note on set-resources-500:
 *   The ocraft DebugInterface has no debugSetMinerals(int)/debugSetVespene(int) method.
 *   The closest available call is debugGiveAllResources() (max out minerals + vespene).
 *   A precise 500-mineral set is not exposed by the ocraft 0.4.21 debug API; this
 *   scenario uses debugGiveAllResources() as the practical approximation and logs
 *   the discrepancy.  DONE_WITH_CONCERNS: exact resource value cannot be set via
 *   ocraft 0.4.21 DebugInterface without dropping to raw SC2APIProtocol requests.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class SC2DebugScenarioRunner implements ScenarioRunner {
    private static final Logger log = Logger.getLogger(SC2DebugScenarioRunner.class);

    // Spawn positions (map-relative, typical two-player map layout)
    // Near player-1 (self) base — used when spawning enemy attackers close to us.
    private static final Point2d NEAR_SELF_BASE     = Point2d.of(30f, 30f);
    // Near player-2 (enemy) base — used when spawning enemy expansion probes there.
    private static final Point2d ENEMY_EXPANSION    = Point2d.of(100f, 100f);
    // Self expansion / supply area
    private static final Point2d SELF_SUPPLY_AREA   = Point2d.of(35f, 35f);

    private static final int PLAYER_SELF  = 1;
    private static final int PLAYER_ENEMY = 2;

    private static final Set<String> AVAILABLE = Set.of(
        "spawn-enemy-attack", "set-resources-500", "supply-almost-capped", "enemy-expands"
    );

    @Inject
    RealSC2Engine engine;

    @Override
    public void run(String scenarioName) {
        SC2BotAgent agent = engine.getBotAgent();
        if (agent == null) {
            throw new IllegalStateException(
                "SC2 not connected — cannot run scenario: " + scenarioName);
        }
        log.infof("[SC2-DEBUG] Running scenario: %s", scenarioName);
        switch (scenarioName) {
            case "spawn-enemy-attack"   -> spawnEnemyAttack(agent);
            case "set-resources-500"    -> setResources(agent);
            case "supply-almost-capped" -> supplyAlmostCapped(agent);
            case "enemy-expands"        -> enemyExpands(agent);
            default -> throw new IllegalArgumentException(
                "Unknown scenario: " + scenarioName + ". Available: " + AVAILABLE);
        }
    }

    @Override
    public Set<String> availableScenarios() {
        return AVAILABLE;
    }

    // -------------------------------------------------------------------------
    // Scenario implementations — each enqueues a Runnable that will be drained
    // by SC2BotAgent.onStep() and flushed with sendDebug().
    // -------------------------------------------------------------------------

    /**
     * Spawns 2 Zealots + 1 Stalker near our base belonging to the enemy player.
     * On the next onStep() frame the units will appear and begin attacking.
     */
    private void spawnEnemyAttack(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing spawn-enemy-attack: 2x Zealot + 1x Stalker for player %d at %s",
            PLAYER_ENEMY, NEAR_SELF_BASE);
        agent.enqueueDebugCommand(() -> {
            // debugCreateUnit(UnitType, Point2d, playerId, count)
            agent.debug().debugCreateUnit(Units.PROTOSS_ZEALOT,  NEAR_SELF_BASE, PLAYER_ENEMY, 2);
            agent.debug().debugCreateUnit(Units.PROTOSS_STALKER, NEAR_SELF_BASE, PLAYER_ENEMY, 1);
        });
    }

    /**
     * Approximates "set minerals=500, vespene=200" via debugGiveAllResources().
     *
     * DONE_WITH_CONCERNS: ocraft 0.4.21 DebugInterface does not expose
     * debugSetMinerals(int) or debugSetVespene(int).  The only available
     * bulk resource call is debugGiveAllResources() which sets both to max.
     * Precise resource values would require raw SC2APIProtocol.RequestDebug
     * with DebugSetUnitValue commands, which is out of scope for Phase 1.
     */
    private void setResources(SC2BotAgent agent) {
        log.warnf("[SC2-DEBUG] set-resources-500: ocraft 0.4.21 has no debugSetMinerals(int). " +
                  "Falling back to debugGiveAllResources() (max minerals + vespene). " +
                  "Exact 500/200 values require raw SC2APIProtocol in a later phase.");
        agent.enqueueDebugCommand(() ->
            agent.debug().debugGiveAllResources()
        );
    }

    /**
     * Spawns 8 Probes for player 1 near the supply area to push supply near cap.
     * A typical Protoss supply cap scenario: ~8 workers added triggers cap pressure.
     */
    private void supplyAlmostCapped(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing supply-almost-capped: 8x Probe for player %d at %s",
            PLAYER_SELF, SELF_SUPPLY_AREA);
        agent.enqueueDebugCommand(() ->
            agent.debug().debugCreateUnit(Units.PROTOSS_PROBE, SELF_SUPPLY_AREA, PLAYER_SELF, 8)
        );
    }

    /**
     * Spawns an enemy Probe at the expansion location to simulate enemy expanding.
     */
    private void enemyExpands(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing enemy-expands: 1x Probe for player %d at %s",
            PLAYER_ENEMY, ENEMY_EXPANSION);
        agent.enqueueDebugCommand(() ->
            agent.debug().debugCreateUnit(Units.PROTOSS_PROBE, ENEMY_EXPANSION, PLAYER_ENEMY, 1)
        );
    }
}
