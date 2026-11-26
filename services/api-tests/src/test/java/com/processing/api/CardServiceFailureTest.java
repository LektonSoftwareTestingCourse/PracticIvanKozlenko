package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@EnabledIfSystemProperty(named = "cards.offline", matches = "true")
class CardServiceFailureTest {
    @Test
    void authorizationIdentifiesUnavailableCardService() {
        var request = payment("400000" + digits(10));

        given()
            .spec(AUTH)
            .body(request)

        .when()
            .post("/api/internal/authorize")

        .then()
            .statusCode(503)
            .body("status", equalTo("DECLINED"), "responseCode", equalTo("96"),
                "declineReason", equalTo("SERVICE_UNAVAILABLE"));

        given()
            .spec(AUTH)
            .body(java.util.Map.of("rrn", digits(12), "pan", request.get("pan"), "amount", 100))

        .when()
            .post("/api/internal/rollback")

        .then()
            .statusCode(503)
            .body("responseCode", equalTo("96"),
                "declineReason", equalTo("SERVICE_UNAVAILABLE"));
    }
}
