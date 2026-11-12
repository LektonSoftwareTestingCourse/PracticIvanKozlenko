package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AuthorizationClientTest {
    private MockRestServiceServer transport;
    private CircuitBreaker breaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        transport = MockRestServiceServer.bindTo(builder).build();
        breaker = CircuitBreaker.ofDefaults("authorization-test");
        var properties = new SwitchProperties("1", Map.of(), "http://authorization", null, null, null,
                new SwitchProperties.RetryProperties(2, List.of(0L)), null);
        var retry = Retry.of("authorization-test", RetryConfig.custom().maxAttempts(2)
                .waitDuration(Duration.ZERO).build());
        client = new AuthorizationClient(properties, builder.build(), retry, breaker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnValidAuthorizationDecision(String status) {
        transport.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\",\"stan\":\"000001\"}", MediaType.APPLICATION_JSON));

        var result = client.authorize(request());

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.stan()).isEqualTo("000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"status\":\"PENDING\"}"})
    void shouldRejectInvalidAuthorizationResponseAfterRetries(String body) {
        transport.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        transport.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Invalid response");
    }

    @Test
    void shouldRecoverWhenFirstAuthorizationAttemptFails() {
        transport.expect(requestTo("http://authorization/api/internal/authorize")).andRespond(withServerError());
        transport.expect(requestTo("http://authorization/api/internal/authorize"))
                .andRespond(withSuccess("{\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));

        var result = client.authorize(request());

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldRejectAuthorizationWhenCircuitIsOpen() {
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnValidRollbackDecision(String status) {
        transport.expect(requestTo("http://authorization/api/internal/rollback"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\",\"rrn\":\"012345678901\"}",
                        MediaType.APPLICATION_JSON));

        var result = client.rollback(request(), "012345678901");

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.rrn()).isEqualTo("012345678901");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"status\":\"PENDING\"}"})
    void shouldReturnNoRollbackWhenResponseIsInvalid(String body) {
        transport.expect(requestTo("http://authorization/api/internal/rollback"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var result = client.rollback(request(), "012345678901");

        assertThat(result).isNull();
    }

    @Test
    void shouldReportDownWhenHealthRequestFails() {
        transport.expect(requestTo("http://authorization/health")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var result = client.checkHealth();

        assertThat(result).isEqualTo("down");
    }

    @Test
    void shouldReportHealthyWhenDependencyResponds() {
        transport.expect(requestTo("http://authorization/health")).andRespond(withSuccess());

        var result = client.checkHealth();

        assertThat(result).isEqualTo("ok");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234560001", "000000", new BigDecimal("100"),
                "643", Instant.parse("2026-10-23T10:00:00Z"), "TERM0001", "POS", "MERCH0000000001",
                "5411", "ACQ001", "ISS001");
    }
}
