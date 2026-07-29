package com.ledgerly.api.expense;

import com.ledgerly.api.ledger.EntryDirection;
import com.ledgerly.api.ledger.LedgerEntry;
import com.ledgerly.api.ledger.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Builds the two balanced entries for a signed expense or supplier credit. */
final class ExpenseLedgerEntries {

  private ExpenseLedgerEntries() {}

  /**
   * Ledger entry amounts are always positive; their direction carries the economic sign. A normal
   * expense debits its category and credits accounts payable. A negative invoice reverses that
   * exact pair, reducing both the expense and the payable without storing negative entry amounts.
   */
  static List<LedgerEntry> forSignedAmount(
      UUID expenseAccountId, UUID liabilityAccountId, long amountMinor, String currency) {
    if (amountMinor == 0) {
      throw new IllegalArgumentException("An expense amount must not be zero");
    }
    long magnitude = amountMinor < 0 ? Math.negateExact(amountMinor) : amountMinor;
    Money amount = Money.of(magnitude, currency);
    EntryDirection expenseDirection = amountMinor < 0 ? EntryDirection.CREDIT : EntryDirection.DEBIT;
    EntryDirection liabilityDirection =
        expenseDirection == EntryDirection.DEBIT ? EntryDirection.CREDIT : EntryDirection.DEBIT;
    return List.of(
        LedgerEntry.of(expenseAccountId, expenseDirection, amount, amount, BigDecimal.ONE),
        LedgerEntry.of(liabilityAccountId, liabilityDirection, amount, amount, BigDecimal.ONE));
  }
}
