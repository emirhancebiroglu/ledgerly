package com.ledgerly.api.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BudgetUsageTest {

  @Test
  void statusUsesExactThresholdsRatherThanRoundedDisplayBurnRate() {
    assertThat(BudgetUsage.of(799_951, 1_000_000).burnRate()).isEqualByComparingTo("0.8000");
    assertThat(BudgetUsage.of(799_951, 1_000_000).status()).isEqualTo(BudgetStatus.ON_TRACK);

    assertThat(BudgetUsage.of(999_951, 1_000_000).burnRate()).isEqualByComparingTo("1.0000");
    assertThat(BudgetUsage.of(999_951, 1_000_000).status()).isEqualTo(BudgetStatus.NEAR_THRESHOLD);
  }
}
