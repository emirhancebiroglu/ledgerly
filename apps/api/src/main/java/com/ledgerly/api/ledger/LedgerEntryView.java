package com.ledgerly.api.ledger;

import java.util.UUID;

/**
 * A read-only projection of one {@code ledger_entry} row for display — account id/name alongside
 * the native amount actually posted, not the full {@link LedgerEntry} domain object. {@link
 * LedgerEntry} has no account name (only {@code accountId}), which the expense-detail screen's
 * ledger-entry rows need to display; a separate read model is simpler than adding a display-only
 * field to the write-side domain type.
 */
public record LedgerEntryView(
    UUID accountId,
    String accountName,
    String direction,
    long amountMinor,
    String currency) {}
