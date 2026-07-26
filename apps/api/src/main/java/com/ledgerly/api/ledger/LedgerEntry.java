package com.ledgerly.api.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A single side of a balanced transaction. Immutable — corrections are made by posting a
 * new reversing entry, never by mutating one that already exists.
 */
public final class LedgerEntry {

  private final UUID accountId;
  private final EntryDirection direction;
  private final Money nativeAmount;
  private final Money baseAmount;
  private final BigDecimal fxRate;

  private LedgerEntry(
      UUID accountId,
      EntryDirection direction,
      Money nativeAmount,
      Money baseAmount,
      BigDecimal fxRate) {
    this.accountId = accountId;
    this.direction = direction;
    this.nativeAmount = nativeAmount;
    this.baseAmount = baseAmount;
    this.fxRate = fxRate;
  }

  public static LedgerEntry of(
      UUID accountId,
      EntryDirection direction,
      Money nativeAmount,
      Money baseAmount,
      BigDecimal fxRate) {
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(nativeAmount, "nativeAmount must not be null");
    Objects.requireNonNull(baseAmount, "baseAmount must not be null");
    Objects.requireNonNull(fxRate, "fxRate must not be null");
    if (nativeAmount.amountMinor() <= 0) {
      throw new IllegalArgumentException("nativeAmount must be positive; direction carries sign");
    }
    if (baseAmount.amountMinor() <= 0) {
      throw new IllegalArgumentException("baseAmount must be positive; direction carries sign");
    }
    return new LedgerEntry(accountId, direction, nativeAmount, baseAmount, fxRate);
  }

  /** Same account/amounts, opposite direction — the other half of a reversing transaction. */
  public LedgerEntry reversed() {
    EntryDirection opposite = direction == EntryDirection.DEBIT
        ? EntryDirection.CREDIT
        : EntryDirection.DEBIT;
    return new LedgerEntry(accountId, opposite, nativeAmount, baseAmount, fxRate);
  }

  public UUID accountId() {
    return accountId;
  }

  public EntryDirection direction() {
    return direction;
  }

  public Money nativeAmount() {
    return nativeAmount;
  }

  public Money baseAmount() {
    return baseAmount;
  }

  public BigDecimal fxRate() {
    return fxRate;
  }

  /** Signed base amount: positive for DEBIT, negative for CREDIT. Used for the balance check. */
  Money signedBaseAmount() {
    return direction == EntryDirection.DEBIT ? baseAmount : baseAmount.negate();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LedgerEntry that)) {
      return false;
    }
    return accountId.equals(that.accountId)
        && direction == that.direction
        && nativeAmount.equals(that.nativeAmount)
        && baseAmount.equals(that.baseAmount)
        && fxRate.equals(that.fxRate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, direction, nativeAmount, baseAmount, fxRate);
  }
}
