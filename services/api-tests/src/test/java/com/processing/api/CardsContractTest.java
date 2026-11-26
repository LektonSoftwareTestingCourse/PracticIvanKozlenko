package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Map;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class CardsContractTest {
    private static final String CARD = "/api/cards/{pan}";

    @Test
    void generatedCardCanBeReadUpdatedAndDeleted() {
        var result = given()
            .spec(GATEWAY)
            .body(Map.of("bins", List.of("400000"), "count", 1))

        .when()
            .post("/api/cards/generate")

        .then()
            .statusCode(201)
            .body("generated", equalTo(1), "cards", hasSize(1))
            .extract()
            .response();
        String pan = result.path("cards[0].pan");

        given()
            .spec(GATEWAY)

        .when()
            .get(CARD, pan)

        .then()
            .statusCode(200)
            .header("Content-Type", containsString("application/json"))
            .body("pan", equalTo(pan));

        given()
            .spec(GATEWAY)
            .body(Map.of("pans", List.of(pan), "status", "BLOCKED"))

        .when()
            .post("/api/cards/bulk-update")

        .then()
            .statusCode(200)
            .body("updated", equalTo(1));

        given()
            .spec(GATEWAY)
            .body(Map.of("status", "ACTIVE"))

        .when()
            .patch(CARD, pan)

        .then()
            .statusCode(200)
            .body("pan", equalTo(pan), "status", equalTo("ACTIVE"));

        given()
            .spec(GATEWAY)

        .when()
            .delete(CARD, pan)

        .then()
            .statusCode(204)
            .body(emptyString());
    }

    @Test
    void reservationAndCompensationAreLinkedByRrn() {
        String pan = createCard();
        var body = Map.of("pan", pan, "rrn", digits(12), "amount", 100);

        given()
            .spec(GATEWAY)
            .body(Map.of("rrn", body.get("rrn"), "amount", 100))

        .when()
            .post(CARD + "/reserve", pan)

        .then()
            .statusCode(200)
            .body("availableBalance.toBigDecimal().intValueExact()", equalTo(9900));

        given()
            .spec(GATEWAY)
            .body(body)

        .when()
            .post(CARD + "/rollback", pan)

        .then()
            .statusCode(200)
            .body("availableBalance.toBigDecimal().intValueExact()", equalTo(10000));

        given()
            .spec(GATEWAY)
            .body(body)

        .when()
            .post(CARD + "/rollback", pan)

        .then()
            .statusCode(409)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
    }

    @ParameterizedTest
    @CsvSource({"currencyCode,64", "currencyCode,6430", "currencyCode,ABC", "bin,12345",
            "bin,1234567", "bin,ABCDEF", "cardholderName,NAME123", "dailyLimit,-1", "monthlyLimit,-1"})
    void rejectsInvalidCardData(String field, String value) {
        var body = cardBody();
        body.put(field, field.endsWith("Limit") ? Integer.valueOf(value) : value);

        given()
            .spec(GATEWAY)
            .body(body)

        .when()
            .post("/api/cards")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
    }

    @Test
    void filteredCardsRespectPageSizeAndBin() {
        createCard();

        given()
            .spec(GATEWAY)
            .queryParams(Map.of("limit", 1, "offset", 0, "bin", "400000", "status", "ACTIVE"))

        .when()
            .get("/api/cards")

        .then()
            .statusCode(200)
            .body("total", greaterThanOrEqualTo(1), "cards", hasSize(1),
                "cards[0].bin", equalTo("400000"), "cards[0].status", equalTo("ACTIVE"));
    }

    @Test
    void unknownPanReturnsDomainError() {
        given()
            .spec(GATEWAY)

        .when()
            .get(CARD, "999999" + digits(10))

        .then()
            .statusCode(404)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
    }

    @Test
    void reservationRejectsOverdraftAndDuplicateReference() {
        String pan = createCard();
        String rrn = digits(12);

        given()
            .spec(GATEWAY)
            .body(Map.of("amount", 10001, "rrn", rrn))

        .when()
            .post(CARD + "/reserve", pan)

        .then()
            .statusCode(402)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
        var reservation = Map.of("amount", 1, "rrn", rrn);

        given()
            .spec(GATEWAY)
            .body(reservation)

        .when()
            .post(CARD + "/reserve", pan)

        .then()
            .statusCode(200)
            .body("pan", equalTo(pan));

        given()
            .spec(GATEWAY)
            .body(reservation)

        .when()
            .post(CARD + "/reserve", pan)

        .then()
            .statusCode(409)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
    }

    @Test
    void excessivePageSizeReturnsConfiguredLimitError() {
        given()
            .spec(GATEWAY)
            .queryParam("limit", 10001)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(413)
            .body("serviceName", equalTo("card-management"), "message", not(emptyString()));
    }

    @Test
    void cardListAcceptsAllDeclaredFilters() {
        var created = given()
            .spec(GATEWAY)
            .body(cardBody())

        .when()
            .post("/api/cards")

        .then()
            .statusCode(201)
            .body("issuerId", notNullValue())
            .extract()
            .response();
        var from = java.time.Instant.parse(created.path("createdAt")).minusSeconds(1);
        var query = Map.of("bin", "400000", "status", "ACTIVE", "issuerId", created.<String>path("issuerId"),
                "startDate", from.toString(), "endDate", from.plusSeconds(2).toString(), "limit", 100, "offset", 0);

        given()
            .spec(GATEWAY)
            .queryParams(query)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(200)
            .body("cards.pan", hasItem(created.<String>path("pan")));
    }
    @Test
    void filtersExcludeOwnCardOnEachMismatch() {
        var card = given()
            .spec(GATEWAY)
            .body(cardBody())

        .when()
            .post("/api/cards")

        .then()
            .statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .extract()
            .response();
        String pan = card.path("pan");
        var time = java.time.Instant.parse(card.path("createdAt"));
        var query = new java.util.LinkedHashMap<String, Object>();
        query.put("bin", "400000");
        query.put("status", "ACTIVE");
        query.put("issuerId", card.path("issuerId"));
        query.put("startDate", time.minusSeconds(1).toString());
        query.put("endDate", time.plusSeconds(1).toString());
        query.put("limit", 10000);

        given()
            .spec(GATEWAY)
            .queryParams(query)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(200)
            .body("cards.pan", hasItem(pan));
        var alternatives = Map.of("bin", "999999", "status", "BLOCKED", "issuerId", "ZZZZZZZZZZ",
                "startDate", time.plusSeconds(1).toString(), "endDate", time.minusSeconds(1).toString());
        var fields = List.copyOf(alternatives.keySet());
        for (int i = 0; i < fields.size(); i++) {
            var mismatch = new java.util.LinkedHashMap<>(query);
            mismatch.put(fields.get(i), alternatives.get(fields.get(i)));

            given()
                .spec(GATEWAY)
                .queryParams(mismatch)

            .when()
                .get("/api/cards")

            .then()
                .statusCode(200)
                .body("cards.pan", not(hasItem(pan)));
            for (int j = i + 1; j < fields.size(); j++) {
                var pair = new java.util.LinkedHashMap<>(mismatch);
                pair.put(fields.get(j), alternatives.get(fields.get(j)));

                given()
                    .spec(GATEWAY)
                    .queryParams(pair)

                .when()
                    .get("/api/cards")

                .then()
                    .statusCode(200)
                    .body("cards.pan", not(hasItem(pan)));
            }
        }

        given()
            .spec(GATEWAY)
            .queryParams("bin", "999999", "limit", 1, "offset", 0)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(200)
            .body("total", equalTo(0), "cards", empty());

        given()
            .spec(GATEWAY)
            .queryParams("limit", 1, "offset", 1000000)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(200)
            .body("cards", empty());
    }

}
