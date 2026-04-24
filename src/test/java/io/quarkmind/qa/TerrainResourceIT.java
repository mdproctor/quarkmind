package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class TerrainResourceIT {

    static {
        try {
            Path cacheDir = Path.of(System.getProperty("user.home"), ".quarkmind", "maps");
            Files.createDirectories(cacheDir);
            Path mapSrc  = Path.of("src/test/resources/maps/TorchesAIE_v4.SC2Map");
            Path mapDest = cacheDir.resolve("TorchesAIE_v4.SC2Map");
            if (!Files.exists(mapDest)) {
                Files.copy(mapSrc, mapDest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot seed test map cache", e);
        }
    }

    @Test
    void terrainEndpointReturnsCorrectDimensionsForTorchesAIE() {
        given()
            .queryParam("map", "TorchesAIE_v4")
            .when().get("/qa/terrain")
            .then()
            .statusCode(200)
            .body("width",  equalTo(160))
            .body("height", equalTo(208))
            .body("walls",      not(empty()))
            .body("highGround", not(empty()))
            .body("ramps",      not(empty()));
    }

    @Test
    void terrainEndpointReturnsCachedOnSecondCall() {
        try { Files.deleteIfExists(Path.of(System.getProperty("user.home"),
                ".quarkmind", "maps", "TorchesAIE_v4-terrain.json")); }
        catch (Exception ignored) {}

        given().queryParam("map", "TorchesAIE_v4").when().get("/qa/terrain").then().statusCode(200);
        given().queryParam("map", "TorchesAIE_v4").when().get("/qa/terrain").then().statusCode(200);

        assertThat(Files.exists(Path.of(System.getProperty("user.home"),
                ".quarkmind", "maps", "TorchesAIE_v4-terrain.json"))).isTrue();
    }

    @Test
    void terrainEndpointReturns404ForUnknownMap() {
        given()
            .queryParam("map", "UnknownMap_v99")
            .when().get("/qa/terrain")
            .then()
            .statusCode(404);
    }
}
