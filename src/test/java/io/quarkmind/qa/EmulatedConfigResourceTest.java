package io.quarkmind.qa;

import io.quarkmind.domain.EnemyStrategy;
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
        config.setEnemyStrategy(EnemyStrategy.defaultProtoss()); // reset strategy
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

    // ---- E4: enemy strategy REST ----

    @Test
    void getEnemyStrategyReturnsDefault() {
        given()
            .when().get("/qa/emulated/config/enemy-strategy")
            .then()
            .statusCode(200)
            .body("buildOrder.size()", equalTo(3))
            .body("buildOrder[0].unitType", equalTo("ZEALOT"))
            .body("loop", equalTo(true))
            .body("mineralsPerTick", equalTo(2))
            .body("attackConfig.armyThreshold", equalTo(3))
            .body("attackConfig.attackIntervalFrames", equalTo(200));
    }

    @Test
    void putEnemyStrategyUpdatesIt() {
        String body = """
            {
              "buildOrder": [{"unitType":"STALKER"},{"unitType":"IMMORTAL"}],
              "loop": false,
              "mineralsPerTick": 5,
              "attackConfig": {"armyThreshold": 2, "attackIntervalFrames": 100}
            }
            """;

        given()
            .contentType("application/json")
            .body(body)
            .when().put("/qa/emulated/config/enemy-strategy")
            .then()
            .statusCode(200);

        given()
            .when().get("/qa/emulated/config/enemy-strategy")
            .then()
            .statusCode(200)
            .body("buildOrder.size()", equalTo(2))
            .body("buildOrder[0].unitType", equalTo("STALKER"))
            .body("loop", equalTo(false))
            .body("mineralsPerTick", equalTo(5));
    }

    @Test
    void putEnemyStrategyWithSingleStep() {
        String body = """
            {
              "buildOrder": [{"unitType":"ZEALOT"}],
              "loop": true,
              "mineralsPerTick": 10,
              "attackConfig": {"armyThreshold": 1, "attackIntervalFrames": 50}
            }
            """;

        given()
            .contentType("application/json")
            .body(body)
            .when().put("/qa/emulated/config/enemy-strategy")
            .then()
            .statusCode(200)
            .body("buildOrder.size()", equalTo(1))
            .body("attackConfig.armyThreshold", equalTo(1));
    }
}
