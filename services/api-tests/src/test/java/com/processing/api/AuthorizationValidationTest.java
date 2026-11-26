package com.processing.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Map;
import java.util.stream.Stream;
import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class AuthorizationValidationTest {
    static Stream<Arguments> invalidRequests() {
        Stream.Builder<Arguments> cases = Stream.builder();
        Map.of("mti", 4, "stan", 6, "pan", 16, "processingCode", 6, "currencyCode", 3,
                "terminalId", 8, "merchantId", 15, "mcc", 4).forEach((field, length) -> {
                    cases.add(Arguments.of(field, "0".repeat(length - 1)));
                    cases.add(Arguments.of(field, "0".repeat(length + 1)));
                    cases.add(Arguments.of(field, " ".repeat(length)));
                });
        for (String required : ("mti stan pan processingCode currencyCode terminalId merchantId mcc acquirerId "
                + "issuerId amount transmissionDateTime").split(" ")) {
            cases.add(Arguments.of(required, null));
        }
        cases.add(Arguments.of("amount", -1));
        cases.add(Arguments.of("transmissionDateTime", "not-an-instant"));
        return cases.build();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestBeforeCardLookup(String field, Object value) {
        var body = payment("999999" + digits(10));
        body.put(field, value);

        given()
            .spec(AUTH)
            .body(body)

        .when()
            .post("/api/internal/authorize")

        .then()
            .statusCode(400)
            .body("status", equalTo(400), "path", equalTo("/api/internal/authorize"),
                "error", equalTo("Bad Request"));
    }
}
