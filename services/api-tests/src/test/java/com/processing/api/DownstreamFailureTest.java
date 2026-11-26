package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@EnabledIfSystemProperty(named = "switch.offline", matches = "true")
class DownstreamFailureTest {
    @Test
    void unavailableSwitchIsIdentifiedAtGateway() {
        var request = payment("400000" + digits(10));

        given()
            .spec(GATEWAY)
            .body(request)

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(503)
            .body("error", equalTo("SERVICE_UNAVAILABLE"), "serviceName", equalTo("switch"));

        given()
            .spec(GATEWAY)

        .when()
            .get("/health")

        .then()
            .statusCode(503)
            .body("status", equalTo("degraded"), "services.switch", equalTo("unavailable"));
    }
}
