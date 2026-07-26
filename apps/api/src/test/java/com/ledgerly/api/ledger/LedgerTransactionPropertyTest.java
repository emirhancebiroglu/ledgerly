package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class LedgerTransactionPropertyTest {

  private static final UUID ORG = UUID.randomUUID();

  @Property
  void balancedRandomEntriesConstruct(
      @ForAll @Size(min = 1, max = 20) List<@IntRange(min = 1, max = 1_000_000) Integer> debitAmounts) {
    List<LedgerEntry> entries = new ArrayList<>();
    long total = 0;
    for (int amount : debitAmounts) {
      entries.add(entry(EntryDirection.DEBIT, amount));
      total += amount;
    }
    entries.add(entry(EntryDirection.CREDIT, total));

    LedgerTransaction tx = LedgerTransaction.post(ORG, "EUR", Instant.now(), entries);

    assertThat(tx.entries()).hasSize(entries.size());
  }

  @Property
  void unbalancedRandomEntriesThrow(
      @ForAll @Size(min = 1, max = 20) List<@IntRange(min = 1, max = 1_000_000) Integer> debitAmounts,
      @ForAll @IntRange(min = 1, max = 1_000_000) int skew) {
    List<LedgerEntry> entries = new ArrayList<>();
    long total = 0;
    for (int amount : debitAmounts) {
      entries.add(entry(EntryDirection.DEBIT, amount));
      total += amount;
    }
    // credit side deliberately off by `skew` so the transaction cannot balance
    entries.add(entry(EntryDirection.CREDIT, total + skew));

    assertThatThrownBy(() -> LedgerTransaction.post(ORG, "EUR", Instant.now(), entries))
        .isInstanceOf(UnbalancedTransactionException.class);
  }

  private LedgerEntry entry(EntryDirection direction, long amountMinor) {
    Money m = Money.of(amountMinor, "EUR");
    return LedgerEntry.of(Arbitraries.create(UUID::randomUUID).sample(), direction, m, m, BigDecimal.ONE);
  }
}
