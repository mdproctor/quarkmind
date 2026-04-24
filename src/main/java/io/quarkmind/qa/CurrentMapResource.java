package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.sc2.SC2Engine;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@UnlessBuildProfile("prod")
@Path("/qa/current-map")
@Produces(MediaType.APPLICATION_JSON)
public class CurrentMapResource {

    @Inject SC2Engine engine;

    @GET
    public Response getCurrentMap() {
        String name = engine.getMapName();
        if (name == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(new MapMeta(name, engine.getMapWidth(), engine.getMapHeight())).build();
    }

    public record MapMeta(String mapName, int mapWidth, int mapHeight) {}
}
