package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class MoneyPropertyTest {

  private static final long SAFE_BOUND = Long.MAX_VALUE / 4;

  @Property
  void addThenSubtractIsIdentity(
      @ForAll @LongRange(min = -SAFE_BOUND, max = SAFE_BOUND) long a,
      @ForAll @LongRange(min = -SAFE_BOUND, max = SAFE_BOUND) long b) {
    Money moneyA = Money.of(a, "USD");
    Money moneyB = Money.of(b, "USD");

    assertThat(moneyA.add(moneyB).subtract(moneyB)).isEqualTo(moneyA);
  }
}
