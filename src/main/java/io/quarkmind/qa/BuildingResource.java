package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.domain.Building;
import io.quarkmind.sc2.SC2Engine;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

@UnlessBuildProfile("prod")
@Path("/qa/building")
@Produces(MediaType.APPLICATION_JSON)
public class BuildingResource {

    @Inject SC2Engine engine;

    @GET @Path("/{tag}")
    public Response getBuilding(@PathParam("tag") String tag) {
        var state = engine.observe();

        Optional<Building> found = state.myBuildings().stream()
            .filter(b -> b.tag().equals(tag))
            .findFirst();

        if (found.isEmpty()) {
            found = state.enemyBuildings().stream()
                .filter(b -> b.tag().equals(tag))
                .findFirst();
        }

        return found
            .map(b -> Response.ok(new BuildingDetail(
                b.tag(), b.type().name(),
                b.health(), b.maxHealth(),
                b.isComplete()
            )).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    public record BuildingDetail(String tag, String type,
                                 int health, int maxHealth,
                                 boolean isComplete) {}
}
