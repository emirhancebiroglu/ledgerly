package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ledgerly.api.ledger.EntryDirection;
import com.ledgerly.api.ledger.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpenseLedgerEntriesTest {

  private static final UUID EXPENSE_ACCOUNT = UUID.randomUUID();
  private static final UUID LIABILITY_ACCOUNT = UUID.randomUUID();

  @Test
  void aNormalExpenseDebitsItsCategoryAndCreditsPayables() {
    List<LedgerEntry> entries =
        ExpenseLedgerEntries.forSignedAmount(EXPENSE_ACCOUNT, LIABILITY_ACCOUNT, 5_000, "EUR");

    assertThat(entries)
        .extracting(LedgerEntry::accountId, LedgerEntry::direction, entry -> entry.nativeAmount().amountMinor())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(EXPENSE_ACCOUNT, EntryDirection.DEBIT, 5_000L),
            org.assertj.core.groups.Tuple.tuple(LIABILITY_ACCOUNT, EntryDirection.CREDIT, 5_000L));
  }

  @Test
  void aCreditNoteReversesDirectionsWhileKeepingEntryMagnitudesPositive() {
    List<LedgerEntry> entries =
        ExpenseLedgerEntries.forSignedAmount(EXPENSE_ACCOUNT, LIABILITY_ACCOUNT, -5_000, "EUR");

    assertThat(entries)
        .extracting(LedgerEntry::accountId, LedgerEntry::direction, entry -> entry.nativeAmount().amountMinor())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(EXPENSE_ACCOUNT, EntryDirection.CREDIT, 5_000L),
            org.assertj.core.groups.Tuple.tuple(LIABILITY_ACCOUNT, EntryDirection.DEBIT, 5_000L));
  }

  @Test
  void aZeroAmountIsNotAPostableExpense() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ExpenseLedgerEntries.forSignedAmount(
                    EXPENSE_ACCOUNT, LIABILITY_ACCOUNT, 0, "EUR"));
  }
}
