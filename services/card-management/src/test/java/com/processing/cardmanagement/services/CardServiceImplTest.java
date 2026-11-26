package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234560001";
    private static final String RRN = "012345678901";
    private static final BigDecimal AMOUNT = new BigDecimal("2500");
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator pans;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService issuers;
    @Mock private RetryService retry;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, rollbacks, settings, defaults, pans, events,
                issuers, retry, new DirectTransactionRunner());
    }

    @Test
    void shouldReserveMoneyAndNotifyWhenReservationIsUnique() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.isUnique(RRN, PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reserve(PAN, AMOUNT, RRN);

        assertThat(result.availableBalance()).isEqualByComparingTo("7500");
        verify(cards).update(argThat(card -> card.pan().equals(PAN)
                && card.availableBalance().compareTo(new BigDecimal("7500")) == 0));
        verify(events).notifyListeners(isA(CardServiceReserveEvent.class));
    }

    @Test
    void shouldNotChangeBalanceWhenReservationAlreadyExists() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldRejectReserveForUnknownCard() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(CardNotFoundException.class);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
    }

    @Test
    void shouldRestoreMoneyAndMarkReservationRolledBack() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(new Reservation(PAN, AMOUNT, RRN)));
        when(rollbacks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.rollback(PAN, AMOUNT, RRN);

        assertThat(result.availableBalance()).isEqualByComparingTo("12500");
        verify(reservations).save(argThat(reservation -> reservation.status() == ReservationStatus.ROLLED_BACK));
        verify(rollbacks).save(argThat(rollback -> rollback.pan().equals(PAN)
                && rollback.rollbackAmount().compareTo(AMOUNT) == 0));
    }

    @Test
    void shouldNotCreditMoneyWhenReservationDoesNotExist() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationNotFoundException.class);
        verify(cards, never()).update(any());
        verify(rollbacks, never()).save(any());
    }

    @Test
    void shouldPreserveUnspecifiedFieldsWhenPatchingCard() {
        var original = activeCard();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(result.dailyLimit()).isEqualTo(original.dailyLimit());
        assertThat(result.monthlyLimit()).isEqualTo(original.monthlyLimit());
        assertThat(result.availableBalance()).isEqualTo(original.availableBalance());
    }

    private Card activeCard() {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN KOZLENKO",
                YearMonth.of(2027, 12), CardStatus.ACTIVE, "643", new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("10000"), "ISS001", Instant.EPOCH);
    }

    private static final class DirectTransactionRunner implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> operation) {
            return operation.apply(this);
        }
    }
}
