package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Duration;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@EnabledIfSystemProperty(named = "broker.offline", matches = "true")
class BrokerRollbackTest {
    @Test
    void failedPublishRestoresBalanceAndLeavesNoSearchResult() {
        String pan = createCard();
        var request = payment(pan);

        given()
            .spec(GATEWAY)
            .body(request)

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("96"), "status", equalTo("DECLINED"),
                "stan", equalTo(request.get("stan")));
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                    given()
                        .spec(GATEWAY)
                        .queryParam("pan", pan)

                    .when()
                        .get(SEARCH)

                    .then()
                        .statusCode(200)
                        .body("total", equalTo(0), "transactions", empty());

                    given()
                        .spec(Api.service("cards", 8081))

                    .when()
                        .get("/api/cards/{pan}", pan)

                    .then()
                        .statusCode(200)
                        .body("availableBalance.toBigDecimal().intValueExact()", equalTo(10000));
                });
    }
}
