package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234560001";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-31T23:59:59Z");
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient bins;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, bins, cards);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999", "10000"})
    void shouldApproveAmountWithinBalanceIncludingExactBoundary(String amount) {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        var request = request(amount);

        var result = service.authorize(request, Instant.now());

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.rrn()).matches("\\d{12}");
        assertThat(result.authCode()).matches("[A-Z0-9]{6}");
        assertThat(result.declineReason()).isNull();
        verify(cards).reserve(new BigDecimal(amount), result.rrn(), PAN);
        verify(events).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalanceByOne() {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));

        var result = service.authorize(request("10001"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("51");
        assertThat(result.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(cards, never()).reserve(any(), any(), any());
        verify(limits, never()).upsertLimitUsage(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "INACTIVE,05,CARD_INACTIVE", "EXPIRED,54,CARD_EXPIRED"})
    void shouldDeclineInactiveCardWithSpecificReason(CardModelStatus status, String code, String reason) {
        stubCard(status, YearMonth.of(2026, 10));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenCardExpiredBeforeTransactionMonth() {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 9));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("54");
        assertThat(result.declineReason()).isEqualTo("CARD_EXPIRED");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineUnknownCardWithoutReservingMoney() {
        when(cards.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("14");
        assertThat(result.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenLimitUpdateRejectsAmount() {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("61");
        assertThat(result.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldApproveWhenConcurrentLimitInsertCanBeRetried() {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("00");
        verify(cards).reserve(new BigDecimal("100"), result.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenLimitStorageFails() {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.responseCode()).isEqualTo("96");
        assertThat(result.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,true"})
    void shouldUseAtomicLimitUpdateResult(int updatedRows, boolean expected) {
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        var date = LocalDate.of(2026, 10, 31);
        when(limits.upsertLimitUsage(PAN, date, new BigDecimal("100"), card.dailyLimit(), card.monthlyLimit()))
                .thenReturn(updatedRows);

        var result = service.checkAndUpdateLimits(card, new BigDecimal("100"), date);

        assertThat(result).isEqualTo(expected);
    }

    private void stubCard(CardModelStatus status, YearMonth expiry) {
        when(cards.getCard(PAN)).thenReturn(card(status, expiry));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.of("ISS001"));
    }

    @ParameterizedTest
    @MethodSource("reservationFailures")
    void shouldReturnSpecificDeclineWhenReservationFails(RuntimeException failure, String code, String reason) {
        stubCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(failure).when(cards).reserve(any(), any(), eq(PAN));

        var result = service.authorize(request("100"), Instant.now());

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        verify(events, never()).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    static Stream<Arguments> reservationFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "14", "CARD_NOT_FOUND"),
                Arguments.of(new InsufficientFundsException("balance changed"), "51", "INSUFFICIENT_FUNDS"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new ReserveException("failed"), "96", "RESERVATION_FAILED"));
    }

    @Test
    void shouldApproveRollbackWhenCardServiceReleasesReservation() {
        var request = new RollbackRequest("012345678901", PAN, new BigDecimal("100"));

        var result = service.rollback(request, Instant.now());

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.rrn()).isEqualTo(request.rrn());
        verify(cards).rollback(request);
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void shouldReturnSpecificDeclineWhenRollbackFails(RuntimeException failure, String code, String reason) {
        var request = new RollbackRequest("012345678901", PAN, new BigDecimal("100"));
        doThrow(failure).when(cards).rollback(request);

        var result = service.rollback(request, Instant.now());

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of(new RollbackConflictException("duplicate"), "05", "ALREADY_ROLLED_BACK"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new RollbackFailureException("failed"), "96", "ROLLBACK_FAILED"));
    }

    private CardModel card(CardModelStatus status, YearMonth expiry) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN KOZLENKO",
                expiry, status, "643", new BigDecimal("100000"), new BigDecimal("500000"), BALANCE, "ISS001",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private AuthorizationRequest request(String amount) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", new BigDecimal(amount), "643",
                TRANSMISSION_TIME, "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }
}
