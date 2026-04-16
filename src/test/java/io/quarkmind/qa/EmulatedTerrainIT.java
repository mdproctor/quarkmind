package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EmulatedTerrainIT {

    @Test
    void terrainEndpointReturnsExpectedDimensions() {
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("width",  equalTo(64))
            .body("height", equalTo(64));
    }

    @Test
    void terrainEndpointHasCorrectWallCount() {
        // Wall at y=18 spans 64 tiles minus 3-tile gap = 61 walls
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("walls.size()", equalTo(61));
    }

    @Test
    void terrainEndpointIncludesWallTile() {
        // [20, 18] is a wall tile (outside the gap)
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("walls.find { it == [20, 18] }", notNullValue());
    }

    @Test
    void terrainEndpointExcludesGapTile() {
        // [12, 18] is in the chokepoint gap — walkable, must NOT appear in walls
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("walls.find { it == [12, 18] }", nullValue());
    }

    @Test
    void terrainEndpointReturnsHighGroundArray() {
        // y=19..63 = 45 rows × 64 cols = 2880 HIGH tiles
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("highGround",        notNullValue())
            .body("highGround.size()", equalTo(45 * 64));
    }

    @Test
    void terrainEndpointReturnsRampArray() {
        // x=11,12,13 at y=18 = 3 ramp tiles
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("ramps",        notNullValue())
            .body("ramps.size()", equalTo(3));
    }

    @Test
    void terrainEndpointHighGroundIncludesStagingTile() {
        // Staging area (26,26) is HIGH ground
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("highGround.find { it == [26, 26] }", notNullValue());
    }

    @Test
    void terrainEndpointRampIncludesChokeGap() {
        // x=11, y=18 is the leftmost ramp tile
        given().when().get("/qa/emulated/terrain")
            .then()
            .statusCode(200)
            .body("ramps.find { it == [11, 18] }", notNullValue());
    }
}
