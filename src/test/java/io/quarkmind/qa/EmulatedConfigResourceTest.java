package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.JsonConfig;
import io.restassured.path.json.config.JsonPathConfig.NumberReturnType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EmulatedConfigResourceTest {

    @Inject EmulatedConfig config;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.config = RestAssured.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(NumberReturnType.DOUBLE));
    }

    @AfterEach
    void resetConfig() {
        config.setWaveSpawnFrame(200);
        config.setWaveUnitCount(4);
        config.setWaveUnitType("ZEALOT");
        config.setUnitSpeed(0.5);
    }

    @Test
    void getReturnsDefaultConfig() {
        given()
            .when().get("/qa/emulated/config")
            .then()
            .statusCode(200)
            .body("waveSpawnFrame", equalTo(200))
            .body("waveUnitCount",  equalTo(4))
            .body("waveUnitType",   equalTo("ZEALOT"))
            .body("unitSpeed",      closeTo(0.5, 0.001));
    }

    @Test
    void putUpdatesUnitSpeed() {
        given()
            .contentType("application/json")
            .body("{\"unitSpeed\": 0.8}")
            .when().put("/qa/emulated/config")
            .then()
            .statusCode(200)
            .body("unitSpeed", closeTo(0.8, 0.001));
    }

    @Test
    void putPartialUpdatePreservesOtherFields() {
        given()
            .contentType("application/json")
            .body("{\"waveUnitCount\": 6}")
            .when().put("/qa/emulated/config")
            .then()
            .statusCode(200)
            .body("waveUnitCount",  equalTo(6))
            .body("waveSpawnFrame", equalTo(200));
    }
}
