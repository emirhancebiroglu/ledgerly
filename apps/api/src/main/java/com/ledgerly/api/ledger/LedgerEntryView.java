package com.ledgerly.api.ledger;

import java.util.UUID;

/**
 * A read-only projection of one {@code ledger_entry} row for display — account id/name alongside
 * the native amount actually posted, not the full {@link LedgerEntry} domain object. Reconstructing
 * {@link LedgerEntry} from a read would mean re-running {@link LedgerTransaction#post}'s balance
 * validation against a row that is already-committed and already-valid; this sidesteps that by
 * never routing a read through the write-side factory at all.
 */
public record LedgerEntryView(
    UUID accountId,
    String accountName,
    String direction,
    long amountMinor,
    String currency) {}
