package com.ledgerly.api.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced double-entry posting. Construction itself enforces the invariant — there is no
 * separate {@code validate()} step and no way to hold an unbalanced instance in memory.
 */
public final class LedgerTransaction {

  private final UUID id;
  private final UUID organizationId;
  private final String baseCurrency;
  private final Instant postedAt;
  private final List<LedgerEntry> entries;

  private LedgerTransaction(
      UUID id,
      UUID organizationId,
      String baseCurrency,
      Instant postedAt,
      List<LedgerEntry> entries) {
    this.id = id;
    this.organizationId = organizationId;
    this.baseCurrency = baseCurrency;
    this.postedAt = postedAt;
    this.entries = entries;
  }

  public static LedgerTransaction post(
      UUID organizationId, String baseCurrency, Instant postedAt, List<LedgerEntry> entries) {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(baseCurrency, "baseCurrency must not be null");
    Objects.requireNonNull(postedAt, "postedAt must not be null");
    Objects.requireNonNull(entries, "entries must not be null");

    List<LedgerEntry> copy = List.copyOf(entries);
    if (copy.isEmpty()) {
      throw new UnbalancedTransactionException("a transaction must have at least one entry");
    }
    if (copy.size() < 2) {
      throw new UnbalancedTransactionException("a single-sided transaction cannot balance");
    }

    Money balance = Money.zero(baseCurrency);
    for (LedgerEntry entry : copy) {
      balance = balance.add(entry.signedBaseAmount());
    }
    if (balance.amountMinor() != 0) {
      throw new UnbalancedTransactionException(
          "entries do not balance in " + baseCurrency + ": net " + balance.amountMinor());
    }

    return new LedgerTransaction(UUID.randomUUID(), organizationId, baseCurrency, postedAt, copy);
  }

  /**
   * A new transaction whose entries negate this one's, posted now. Pairs with the original to
   * sum to zero — the only way to correct a posted transaction.
   */
  public LedgerTransaction reverse(Instant postedAt) {
    List<LedgerEntry> reversedEntries = new ArrayList<>(entries.size());
    for (LedgerEntry entry : entries) {
      reversedEntries.add(entry.reversed());
    }
    return post(organizationId, baseCurrency, postedAt, reversedEntries);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String baseCurrency() {
    return baseCurrency;
  }

  public Instant postedAt() {
    return postedAt;
  }

  public List<LedgerEntry> entries() {
    return Collections.unmodifiableList(entries);
  }
}
