package com.processing.api;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

final class Api {
    static final RequestSpecification GATEWAY = service("gateway", 8080);
    static final RequestSpecification LOGGER = service("logger", 8088);
    static final RequestSpecification AUTH = service("authorization", 8083);
    static final RequestSpecification SWITCH = service("switch", 8082);
    static final String SEARCH = "/api/transactions/search";
    static final String LOG = "/api/internal/log";

    private Api() {
    }

    static RequestSpecification service(String name, int port) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty(name + ".url", "http://localhost:" + (port + 10000)))
                .setContentType(ContentType.JSON).setAccept(ContentType.ANY).build();
    }

    static String digits(int count) {
        return String.format(Locale.ROOT, "%0" + count + "d",
                ThreadLocalRandom.current().nextLong((long) Math.pow(10, count)));
    }

    static Map<String, Object> cardBody() {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "API TEST",
                "currencyCode", "643", "initialBalance", 10000, "dailyLimit", 8000,
                "monthlyLimit", 9000));
    }

    static String createCard() {
        return given()
            .spec(GATEWAY)
            .body(cardBody())

        .when()
            .post("/api/cards")

        .then()
            .statusCode(201)
            .body("pan", matchesPattern("[0-9]{16}"),
                "status", equalTo("ACTIVE"))
            .extract()
            .path("pan");
    }

    static Map<String, Object> payment(String pan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("pan", pan);
        body.put("stan", digits(6));
        body.put("processingCode", "000000");
        body.put("currencyCode", "643");
        body.put("amount", 100);
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "M" + digits(14));
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        body.put("transmissionDateTime", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return body;
    }

    static Map<String, Object> logEntry() {
        Map<String, Object> entry = payment("400000" + digits(10));
        entry.put("id", UUID.randomUUID().toString());
        entry.put("createdAt", entry.get("transmissionDateTime"));
        entry.put("status", "APPROVED");
        entry.put("rrn", digits(12));
        entry.put("authCode", "ABC123");
        entry.put("processingTimeMs", 1);
        return entry;
    }
}
