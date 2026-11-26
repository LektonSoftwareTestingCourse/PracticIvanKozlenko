package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class AuthorizationContractTest {
    private static final String AUTHORIZE = "/api/internal/authorize";

    @ParameterizedTest
    @CsvSource({
            "BLOCKED,100,403,05,CARD_BLOCKED", "INACTIVE,100,403,05,CARD_INACTIVE",
            "EXPIRED,100,403,54,CARD_EXPIRED", "FUTURE_DATE,100,403,54,CARD_EXPIRED",
            "ACTIVE,10001,422,51,INSUFFICIENT_FUNDS", "LOW_BALANCE,100,422,51,INSUFFICIENT_FUNDS",
            "ACTIVE,8001,400,61,EXCEEDS_AMOUNT_LIMIT", "LOW_LIMIT,100,400,61,EXCEEDS_AMOUNT_LIMIT",
            "MISSING,100,404,14,CARD_NOT_FOUND"})
    void declineIdentifiesBothCodeAndReason(String state, int amount, int http, String code, String reason) {
        String pan = "MISSING".equals(state) ? "999999" + digits(10) : createCard();
        var body = payment(pan);
        body.put("amount", amount);
        switch (state) {
            case "BLOCKED", "INACTIVE", "EXPIRED" ->
                    given()
                        .spec(GATEWAY)
                        .body(Map.of("status", state))

                    .when()
                        .patch("/api/cards/{pan}", pan)

                    .then()
                        .statusCode(200)
                        .body("status", equalTo(state));
            case "FUTURE_DATE" -> body.put("transmissionDateTime", "2099-01-01T00:00:00Z");
            case "LOW_BALANCE", "LOW_LIMIT" -> {
                String field = "LOW_BALANCE".equals(state) ? "availableBalance" : "dailyLimit";

                given()
                    .spec(GATEWAY)
                    .body(Map.of(field, 99))

                .when()
                    .patch("/api/cards/{pan}", pan)

                .then()
                    .statusCode(200)
                    .body("pan", equalTo(pan));
            }
            default -> { }
        }

        given()
            .spec(AUTH)
            .body(body)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(http)
            .body("responseCode", equalTo(code), "declineReason", equalTo(reason),
                "status", equalTo("DECLINED"), "authCode", nullValue(), "stan", equalTo(body.get("stan")));
    }

    @Test
    void gatewayPaymentBecomesSearchable() {
        String pan = createCard();
        var request = payment(pan);

        String rrn = given()
            .spec(GATEWAY)
            .body(request)

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(200)
            .body("status", equalTo("APPROVED"), "responseCode", equalTo("00"),
                "authCode", matchesPattern("[A-Z0-9]{6}"), "rrn", matchesPattern("[0-9]{12}"),
                "stan", equalTo(request.get("stan")), "declineReason", nullValue(),
                "processingTimeMs", greaterThanOrEqualTo(0))
            .extract()
            .path("rrn");

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                given()
                    .spec(GATEWAY)
                    .queryParam("pan", pan)

                .when()
                    .get(SEARCH)

                .then()
                    .statusCode(200)
                    .body("transactions.findAll { it.rrn == '" + rrn
                        + "' && it.stan == '" + request.get("stan") + "' && it.status == 'APPROVED' }", hasSize(1)));
    }

    @Test
    void unknownBinDeclinesAtSwitch() {
        var request = payment("999999" + digits(10));

        given()
            .spec(SWITCH)
            .body(request)

        .when()
            .post("/api/internal/route")

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"),
                "stan", equalTo(request.get("stan")));
    }

    @Test
    void rollbackRestoresReservationAndRejectsReplay() {
        String pan = createCard();
        var request = payment(pan);

        String rrn = given()
            .spec(AUTH)
            .body(request)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("00"))
            .extract()
            .path("rrn");
        var rollback = Map.of("rrn", rrn, "amount", request.get("amount"), "pan", pan);

        given()
            .spec(AUTH)
            .body(rollback)

        .when()
            .post("/api/internal/rollback")

        .then()
            .statusCode(200)
            .body("rrn", equalTo(rrn), "status", equalTo("APPROVED"));

        given()
            .spec(AUTH)
            .body(rollback)

        .when()
            .post("/api/internal/rollback")

        .then()
            .statusCode(409)
            .body("responseCode", equalTo("05"),
                "declineReason", equalTo("ALREADY_ROLLED_BACK"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                        given()
                            .spec(Api.service("cards", 8081))

                        .when()
                            .get("/api/cards/{pan}", pan)

                        .then()
                            .statusCode(200)
                            .body("availableBalance.toBigDecimal().intValueExact()", equalTo(10000)));
    }

    @Test
    void rollbackRequiresAnExistingReservation() {
        String pan = createCard();
        var rollback = Map.of("pan", pan, "amount", 1, "rrn", digits(12));

        given()
            .spec(AUTH)
            .body(rollback)

        .when()
            .post("/api/internal/rollback")

        .then()
            .statusCode(404)
            .body("status", equalTo("DECLINED"), "responseCode", equalTo("14"),
                "declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }
    @ParameterizedTest
    @CsvSource({"dailyLimit", "monthlyLimit"})
    void cumulativeLimitAcceptsBoundaryAndRejectsNextUnit(String limit) {
        String pan = createCard();

        given()
            .spec(GATEWAY)
            .body("monthlyLimit".equals(limit)
                ? Map.of("dailyLimit", 100, "monthlyLimit", 100) : Map.of(limit, 100))

        .when()
            .patch("/api/cards/{pan}", pan)

        .then()
            .statusCode(200)
            .body("pan", equalTo(pan));
        var first = payment(pan);
        var day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1);
        first.put("transmissionDateTime", day + "T12:00:00Z");

        given()
            .spec(AUTH)
            .body(first)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("00"));
        var next = payment(pan);
        next.put("transmissionDateTime", ("monthlyLimit".equals(limit) ? day.plusDays(1) : day) + "T12:00:00Z");
        next.put("amount", 1);

        given()
            .spec(AUTH)
            .body(next)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(400)
            .body("responseCode", equalTo("61"),
                "declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "EXPIRED,54,CARD_EXPIRED", "INACTIVE,05,CARD_INACTIVE"})
    void gatewayPreservesBusinessDecline(String status, String code, String reason) {
        String pan = createCard();

        given()
            .spec(GATEWAY)
            .body(Map.of("status", status))

        .when()
            .patch("/api/cards/{pan}", pan)

        .then()
            .statusCode(200)
            .body("status", equalTo(status));

        given()
            .spec(GATEWAY)
            .body(payment(pan))

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(200)
            .body("status", equalTo("DECLINED"),
                "responseCode", equalTo(code), "declineReason", equalTo(reason), "authCode", nullValue());
    }

    @Test
    void recordsKnownMtiDiscrepancy() {
        given()
            .spec(GATEWAY)
            .body(payment(createCard()))

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(200)
            .body("status", equalTo("APPROVED"), "mti", equalTo("0100"));
    }

    @Test
    void recordsMissingAuthorizationDeduplication() {
        String pan = createCard();
        var request = payment(pan);

        String first = given()
            .spec(AUTH)
            .body(request)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("00"))
            .extract()
            .path("rrn");

        given()
            .spec(AUTH)
            .body(request)

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(200)
            .body("responseCode", equalTo("00"), "rrn", not(equalTo(first)));

        given()
            .spec(Api.service("cards", 8081))

        .when()
            .get("/api/cards/{pan}", pan)

        .then()
            .statusCode(200)
            .body("availableBalance.toBigDecimal().intValueExact()", equalTo(9800));
    }

    @Test
    void deletedCardIsReportedAsMissing() {
        String pan = createCard();

        given()
            .spec(GATEWAY)

        .when()
            .delete("/api/cards/{pan}", pan)

        .then()
            .statusCode(204)
            .body(emptyString());

        given()
            .spec(AUTH)
            .body(payment(pan))

        .when()
            .post(AUTHORIZE)

        .then()
            .statusCode(404)
            .body("status", equalTo("DECLINED"),
                "responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"));
    }

}
