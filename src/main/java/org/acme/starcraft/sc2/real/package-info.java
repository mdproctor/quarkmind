/**
 * Real StarCraft II API implementation — active only in the %sc2 Quarkus profile.
 * All CDI beans annotated @IfBuildProfile("sc2").
 *
 * Requires StarCraft II installed locally. RealSC2Client auto-launches SC2 via
 * ocraft-s2client's S2Coordinator.
 *
 * Two-loop architecture:
 *  - ocraft's S2Coordinator game loop (SC2 frame speed ~22fps): SC2BotAgent.onStep()
 *    stores the latest GameState and drains IntentQueue, sending commands to SC2.
 *  - AgentOrchestrator @Scheduled loop (500ms): reads GameState via RealGameObserver,
 *    runs CaseEngine, writes Intents to IntentQueue.
 *
 * @see org.acme.starcraft.sc2.mock for the mock implementations used in development and testing.
 */
package org.acme.starcraft.sc2.real;
