package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@UnlessBuildProfile("prod")
@Path("/qa/sprites")
public class SpriteProxyResource {

    private static final Logger log = Logger.getLogger(SpriteProxyResource.class);

    private static final String BASE = "https://liquipedia.net/commons/images/";

    private static final Map<String, String> PATHS = Map.of(
        "SC2Probe.jpg",   "4/4f/SC2Probe.jpg",
        "SC2Nexus.jpg",   "f/f8/SC2Nexus.jpg",
        "SC2Pylon.jpg",   "4/48/SC2Pylon.jpg",
        "SC2Gateway.jpg", "9/99/SC2Gateway.jpg",
        "SC2Zealot.jpg",  "5/5c/SC2Zealot.jpg",
        "SC2Stalker.jpg", "6/63/SC2Stalker.jpg"
    );

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    @GET
    @Path("/{name}")
    public Response getSprite(@PathParam("name") String name) {
        if (!PATHS.containsKey(name)) {
            return Response.status(404).build();
        }
        try {
            byte[] data = cache.computeIfAbsent(name, this::fetch);
            return Response.ok(data).type("image/jpeg").build();
        } catch (Exception e) {
            log.errorf(e, "[VISUALIZER] Sprite fetch failed for %s: %s", name, e.getMessage());
            return Response.serverError().build();
        }
    }

    private byte[] fetch(String name) {
        try (InputStream in = new URL(BASE + PATHS.get(name)).openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch " + name, e);
        }
    }
}
