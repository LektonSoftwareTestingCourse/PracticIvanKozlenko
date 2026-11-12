package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    private static final String PAN = "4000001234560001";
    private static final String RRN = "012345678901";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-23T10:00:00Z");
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties("1", Map.of("400000", "ISS001"), null, null, null, null, null, null);
        service = new RouteService(new RoutingService(properties), authorization, fees, logger);
    }

    @Test
    void shouldPublishApprovedTransactionWithIssuerAndFeeWhenAuthorizationSucceeds() {
        var request = request(PAN, "TERM1", "MERCH1");
        var approved = approved(12);
        when(authorization.authorize(any())).thenReturn(approved);
        when(fees.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("2.50"));
        when(logger.log(any())).thenReturn(true);

        var result = service.route(request);

        assertThat(result).isEqualTo(approved);
        verify(logger).log(any());
        verify(logger).log(argThat(tx -> tx != null
                && PAN.equals(tx.pan())
                && "000001".equals(tx.stan())
                && "0100".equals(tx.mti())
                && "ISS001".equals(tx.issuerId())
                && "TERM1000".equals(tx.terminalId())
                && "MERCH1000000000".equals(tx.merchantId())
                && tx.amount() != null && tx.amount().compareTo(new BigDecimal("100")) == 0
                && tx.acquiringFee() != null && tx.acquiringFee().compareTo(new BigDecimal("2.50")) == 0
                && tx.status() == TransactionStatus.APPROVED
                && RRN.equals(tx.rrn())
                && "ABC123".equals(tx.authCode())
                && TRANSMISSION_TIME.equals(tx.transmissionDateTime())));
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @MethodSource("rollbackResponses")
    void shouldRollbackAndDeclineWhenPublishingApprovedTransactionFails(RollbackResponse rollback) {
        when(authorization.authorize(any())).thenReturn(approved(null));
        when(authorization.rollback(any(), eq(RRN))).thenReturn(rollback);

        var result = service.route(request(PAN, "TERM0001", "MERCH0000000001"));

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("96");
        verify(authorization).rollback(any(), eq(RRN));
        verify(authorization).rollback(argThat(original -> original != null
                && PAN.equals(original.pan())
                && original.amount() != null && original.amount().compareTo(new BigDecimal("100")) == 0
                && "ISS001".equals(original.issuerId())), eq(RRN));
    }

    static Stream<Arguments> rollbackResponses() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new RollbackResponse(RRN, "00", "APPROVED", null, 0)),
                Arguments.of(new RollbackResponse(RRN, "96", "DECLINED", "SERVICE_UNAVAILABLE", 0)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveDeclineWithoutRollbackRegardlessOfLoggingResult(boolean logged) {
        var declined = new AuthorizationResponse("0110", "000001", null, null, "51", "DECLINED",
                "INSUFFICIENT_FUNDS", 0);
        when(authorization.authorize(any())).thenReturn(declined);
        when(logger.log(any())).thenReturn(logged);

        var result = service.route(request(PAN, "TERM0001", "MERCH0000000001"));

        assertThat(result).isEqualTo(declined);
        verify(logger).log(argThat(tx -> tx.status() == TransactionStatus.DECLINED
                && "INSUFFICIENT_FUNDS".equals(tx.declineReason())));
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "9999991234560001"})
    void shouldDeclineUnknownOrShortPan(String pan) {
        var result = service.route(request(pan, "TERM0001", "MERCH0000000001"));

        assertThat(result.responseCode()).isEqualTo("14");
        assertThat(result.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(authorization, never()).rollback(any(), any());
        verify(logger).log(argThat(tx -> tx.status() == TransactionStatus.DECLINED));
    }

    @Test
    void shouldDeclineWhenAuthorizationIsUnavailable() {
        when(authorization.authorize(any())).thenThrow(new AuthorizationException("000001", 3, "timeout"));

        var result = service.route(request(PAN, "TERM0001", "MERCH0000000001"));

        assertThat(result.responseCode()).isEqualTo("05");
        assertThat(result.status()).isEqualTo("DECLINED");
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @MethodSource("identifiers")
    void shouldNormalizeIdentifiersInPublishedTransaction(String terminal, String merchant,
                                                          String expectedTerminal, String expectedMerchant) {
        when(authorization.authorize(any())).thenReturn(approved(0));
        when(logger.log(any())).thenReturn(true);

        service.route(request(PAN, terminal, merchant));

        verify(logger).log(any());
        verify(logger).log(argThat(tx -> tx != null
                && Objects.equals(tx.terminalId(), expectedTerminal)
                && Objects.equals(tx.merchantId(), expectedMerchant)));
    }

    static Stream<Arguments> identifiers() {
        return Stream.of(
                Arguments.of(null, null, null, null),
                Arguments.of("", "", "", ""),
                Arguments.of(" ", " ", " ", " "),
                Arguments.of("T", "M", "T0000000", "M00000000000000"),
                Arguments.of("1234567", "12345678901234", "12345670", "123456789012340"),
                Arguments.of("12345678", "123456789012345", "12345678", "123456789012345"),
                Arguments.of("123456789", "1234567890123456", "12345678", "123456789012345"),
                Arguments.of("TERM0001", "MERCH00000000001", "TERM0001", "MERCH0000000001"),
                Arguments.of("TERM0001", "MERCH10000000001", "TERM0001", "MERCH1000000000"),
                Arguments.of("TERM0001", "MERCH000000000001", "TERM0001", "MERCH0000000000"));
    }

    private AuthorizationResponse approved(Integer duration) {
        return new AuthorizationResponse("0110", "000001", RRN, "ABC123", "00", "APPROVED", null, duration);
    }

    private AuthorizationRequest request(String pan, String terminal, String merchant) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", new BigDecimal("100"), "643",
                TRANSMISSION_TIME, terminal, "POS", merchant, "5411", "ACQ001", null);
    }
}
