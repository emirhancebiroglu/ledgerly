package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void addsSameCurrency() {
    Money a = Money.of(1_000, "USD");
    Money b = Money.of(250, "USD");

    assertThat(a.add(b)).isEqualTo(Money.of(1_250, "USD"));
  }

  @Test
  void subtractsSameCurrency() {
    Money a = Money.of(1_000, "USD");
    Money b = Money.of(250, "USD");

    assertThat(a.subtract(b)).isEqualTo(Money.of(750, "USD"));
  }

  @Test
  void negates() {
    assertThat(Money.of(500, "USD").negate()).isEqualTo(Money.of(-500, "USD"));
  }

  @Test
  void addRejectsCurrencyMismatch() {
    Money usd = Money.of(100, "USD");
    Money eur = Money.of(100, "EUR");

    assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void subtractRejectsCurrencyMismatch() {
    Money usd = Money.of(100, "USD");
    Money eur = Money.of(100, "EUR");

    assertThatThrownBy(() -> usd.subtract(eur)).isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void rejectsUnknownIsoCode() {
    assertThatThrownBy(() -> Money.of(100, "XXX_NOT_REAL"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hasNoPublicZeroArgConstructor() {
    for (Constructor<?> constructor : Money.class.getDeclaredConstructors()) {
      boolean isPublic = Modifier.isPublic(constructor.getModifiers());
      boolean isZeroArg = constructor.getParameterCount() == 0;
      assertThat(isPublic && isZeroArg)
          .as("Money must not expose a public zero-arg constructor")
          .isFalse();
    }
  }

  @Test
  void multiplyRoundsHalfUpAtMinorUnit() {
    Money amount = Money.of(100, "USD");

    // 100 * 1.005 = 100.5 -> rounds to 101 (HALF_UP), never a double
    assertThat(amount.multiply(new BigDecimal("1.005"))).isEqualTo(Money.of(101, "USD"));
    // 100 * 1.004 = 100.4 -> rounds to 100
    assertThat(amount.multiply(new BigDecimal("1.004"))).isEqualTo(Money.of(100, "USD"));
  }

  @Test
  void addOverflowThrowsRatherThanWrapping() {
    Money max = Money.of(Long.MAX_VALUE, "USD");
    Money one = Money.of(1, "USD");

    assertThatThrownBy(() -> max.add(one)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void subtractOverflowThrowsRatherThanWrapping() {
    Money min = Money.of(Long.MIN_VALUE, "USD");
    Money one = Money.of(1, "USD");

    assertThatThrownBy(() -> min.subtract(one)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void negateOverflowThrowsRatherThanWrapping() {
    Money min = Money.of(Long.MIN_VALUE, "USD");

    assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
  }
}
