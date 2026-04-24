package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.SC2Engine;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

@UnlessBuildProfile("prod")
@Path("/qa/unit")
@Produces(MediaType.APPLICATION_JSON)
public class UnitResource {

    @Inject SC2Engine engine;

    @GET @Path("/{tag}")
    public Response getUnit(@PathParam("tag") String tag) {
        var state = engine.observe();

        Optional<Unit> found = state.myUnits().stream()
            .filter(u -> u.tag().equals(tag))
            .findFirst();

        if (found.isEmpty()) {
            found = state.enemyUnits().stream()
                .filter(u -> u.tag().equals(tag))
                .findFirst();
        }

        return found
            .map(u -> Response.ok(new UnitDetail(
                u.tag(), u.type().name(),
                u.health(), u.maxHealth(),
                u.shields(), u.maxShields()
            )).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    public record UnitDetail(String tag, String type,
                             int health, int maxHealth,
                             int shields, int maxShields) {}
}
