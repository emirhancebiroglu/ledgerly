package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTransactionTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID CASH_ACCOUNT = UUID.randomUUID();
  private static final UUID EXPENSE_ACCOUNT = UUID.randomUUID();
  private static final BigDecimal RATE_1_1 = BigDecimal.ONE;

  private LedgerEntry debit(UUID account, long amountMinor) {
    Money m = Money.of(amountMinor, "EUR");
    return LedgerEntry.of(account, EntryDirection.DEBIT, m, m, RATE_1_1);
  }

  private LedgerEntry credit(UUID account, long amountMinor) {
    Money m = Money.of(amountMinor, "EUR");
    return LedgerEntry.of(account, EntryDirection.CREDIT, m, m, RATE_1_1);
  }

  @Test
  void balancedTransactionConstructs() {
    LedgerTransaction tx = LedgerTransaction.post(
        ORG, "EUR", Instant.now(),
        List.of(debit(EXPENSE_ACCOUNT, 1_000), credit(CASH_ACCOUNT, 1_000)));

    assertThat(tx.entries()).hasSize(2);
  }

  @Test
  void unbalancedTransactionThrowsAtConstruction() {
    assertThatThrownBy(() -> LedgerTransaction.post(
        ORG, "EUR", Instant.now(),
        List.of(debit(EXPENSE_ACCOUNT, 1_000), credit(CASH_ACCOUNT, 900))))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  @Test
  void emptyEntryListRejected() {
    assertThatThrownBy(() -> LedgerTransaction.post(ORG, "EUR", Instant.now(), List.of()))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  @Test
  void singleSidedTransactionRejected() {
    assertThatThrownBy(() -> LedgerTransaction.post(
        ORG, "EUR", Instant.now(), List.of(debit(EXPENSE_ACCOUNT, 1_000))))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  @Test
  void entriesAreDefensivelyCopied() {
    List<LedgerEntry> mutable = new ArrayList<>();
    mutable.add(debit(EXPENSE_ACCOUNT, 1_000));
    mutable.add(credit(CASH_ACCOUNT, 1_000));

    LedgerTransaction tx = LedgerTransaction.post(ORG, "EUR", Instant.now(), mutable);
    mutable.add(debit(EXPENSE_ACCOUNT, 500));

    assertThat(tx.entries()).hasSize(2);
  }

  @Test
  void entriesListIsUnmodifiable() {
    LedgerTransaction tx = LedgerTransaction.post(
        ORG, "EUR", Instant.now(),
        List.of(debit(EXPENSE_ACCOUNT, 1_000), credit(CASH_ACCOUNT, 1_000)));

    assertThatThrownBy(() -> tx.entries().add(debit(EXPENSE_ACCOUNT, 1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ledgerEntryHasNoSetters() {
    for (var method : LedgerEntry.class.getDeclaredMethods()) {
      assertThat(method.getName())
          .as("LedgerEntry must not expose a setter")
          .doesNotStartWith("set");
    }
  }

  @Test
  void ledgerEntryFieldsAreFinal() {
    for (Field field : LedgerEntry.class.getDeclaredFields()) {
      assertThat(Modifier.isFinal(field.getModifiers()))
          .as("LedgerEntry field '%s' must be final", field.getName())
          .isTrue();
    }
  }

  @Test
  void reverseProducesEntriesThatNegateTheOriginal() {
    LedgerTransaction original = LedgerTransaction.post(
        ORG, "EUR", Instant.now(),
        List.of(debit(EXPENSE_ACCOUNT, 1_000), credit(CASH_ACCOUNT, 1_000)));

    LedgerTransaction reversal = original.reverse(Instant.now());

    assertThat(reversal.entries()).hasSize(2);
    assertThat(reversal.entries().get(0).direction())
        .isEqualTo(original.entries().get(0).direction() == EntryDirection.DEBIT
            ? EntryDirection.CREDIT
            : EntryDirection.DEBIT);
  }

  @Test
  void reversalAndOriginalPairSumToZero() {
    LedgerTransaction original = LedgerTransaction.post(
        ORG, "EUR", Instant.now(),
        List.of(debit(EXPENSE_ACCOUNT, 1_000), credit(CASH_ACCOUNT, 1_000)));
    LedgerTransaction reversal = original.reverse(Instant.now());

    Money net = Money.zero("EUR");
    for (LedgerEntry e : original.entries()) {
      net = net.add(signed(e));
    }
    for (LedgerEntry e : reversal.entries()) {
      net = net.add(signed(e));
    }

    assertThat(net.amountMinor()).isZero();
  }

  private Money signed(LedgerEntry e) {
    return e.direction() == EntryDirection.DEBIT ? e.baseAmount() : e.baseAmount().negate();
  }
}
