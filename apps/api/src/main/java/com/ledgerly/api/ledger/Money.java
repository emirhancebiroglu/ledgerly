package com.ledgerly.api.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Exact money as minor units of an ISO-4217 currency. No floating point anywhere.
 */
public final class Money {

  private final long amountMinor;
  private final String currency;

  private Money(long amountMinor, String currency) {
    this.amountMinor = amountMinor;
    this.currency = currency;
  }

  public static Money of(long amountMinor, String currencyCode) {
    Objects.requireNonNull(currencyCode, "currencyCode must not be null");
    // throws IllegalArgumentException for an unknown ISO 4217 code
    Currency currency = Currency.getInstance(currencyCode);
    return new Money(amountMinor, currency.getCurrencyCode());
  }

  public static Money zero(String currencyCode) {
    return of(0L, currencyCode);
  }

  public long amountMinor() {
    return amountMinor;
  }

  public String currency() {
    return currency;
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(this.amountMinor, other.amountMinor), currency);
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(this.amountMinor, other.amountMinor), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(this.amountMinor), currency);
  }

  /**
   * Multiplies by a rate, rounding HALF_UP at the minor unit. Used to convert a native
   * amount into its base-currency equivalent at a frozen FX rate.
   */
  public Money multiply(BigDecimal rate) {
    Objects.requireNonNull(rate, "rate must not be null");
    BigDecimal result = BigDecimal.valueOf(amountMinor)
        .multiply(rate)
        .setScale(0, RoundingMode.HALF_UP);
    return new Money(result.longValueExact(), currency);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other must not be null");
    if (!this.currency.equals(other.currency)) {
      throw new CurrencyMismatchException(this.currency, other.currency);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Money money)) {
      return false;
    }
    return amountMinor == money.amountMinor && currency.equals(money.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(amountMinor, currency);
  }

  @Override
  public String toString() {
    return amountMinor + " " + currency;
  }
}
