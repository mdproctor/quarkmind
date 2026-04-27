package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.replay.ReplayEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@UnlessBuildProfile("prod")
@Path("/qa/replay")
public class ReplayControlsResource {

    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;
    @Inject GameStateBroadcaster broadcaster;

    @GET @Path("/status") @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        if (!(engine instanceof ReplayEngine re)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new ReplayStatusResponse(
            re.currentLoop(), re.totalLoops(),
            orchestrator.isSchedulerPaused(), orchestrator.getSpeedMultiplier()
        )).build();
    }

    @POST @Path("/pause")
    public Response pause() {
        orchestrator.pauseScheduler();
        return Response.noContent().build();
    }

    @POST @Path("/resume")
    public Response resume() {
        orchestrator.resumeScheduler();
        return Response.noContent().build();
    }

    @POST @Path("/seek")
    public Response seek(@QueryParam("loop") long targetLoop) {
        if (!(engine instanceof ReplayEngine re)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        broadcaster.setSuppressed(true);
        try {
            re.seekTo(targetLoop);
        } finally {
            broadcaster.setSuppressed(false);
        }
        return Response.noContent().build();
    }

    @GET @Path("/snapshot") @Produces(MediaType.APPLICATION_JSON)
    public Response snapshot() {
        var state = engine.observe();
        // Count by type for unit breakdown
        var myUnitCounts = new java.util.TreeMap<String, Long>();
        state.myUnits().forEach(u -> myUnitCounts.merge(u.type().name(), 1L, Long::sum));
        var enemyUnitCounts = new java.util.TreeMap<String, Long>();
        state.enemyUnits().forEach(u -> enemyUnitCounts.merge(u.type().name(), 1L, Long::sum));
        var enemyBldgCounts = new java.util.TreeMap<String, Long>();
        state.enemyBuildings().forEach(b -> enemyBldgCounts.merge(b.type().name(), 1L, Long::sum));
        var myBldgCounts = new java.util.TreeMap<String, Long>();
        state.myBuildings().forEach(b -> myBldgCounts.merge(b.type().name(), 1L, Long::sum));

        return Response.ok(new java.util.LinkedHashMap<String, Object>() {{
            put("loop",           state.gameFrame() * 22);
            put("minerals",       state.minerals());
            put("vespene",        state.vespene());
            put("supply",         state.supplyUsed() + "/" + state.supply());
            put("mineralPatches", state.mineralPatches().size());
            put("geysers",        state.geysers().size());
            put("myUnits",        myUnitCounts);
            put("myBuildings",    myBldgCounts);
            put("enemyUnits",     enemyUnitCounts);
            put("enemyBuildings", enemyBldgCounts);
        }}).build();
    }

    @POST @Path("/speed")
    public Response speed(@QueryParam("multiplier") int multiplier) {
        if (multiplier < 0 || multiplier > 8) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        orchestrator.setSpeedMultiplier(multiplier);
        return Response.noContent().build();
    }
}
