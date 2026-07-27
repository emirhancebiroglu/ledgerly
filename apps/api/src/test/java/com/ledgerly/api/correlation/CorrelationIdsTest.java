package com.ledgerly.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdsTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(CorrelationIdHolder.MDC_KEY);
  }

  @Test
  void aUuidShapedHeaderRoundTripsUnchanged() {
    UUID header = UUID.randomUUID();
    MDC.put(CorrelationIdHolder.MDC_KEY, header.toString());

    assertThat(CorrelationIds.current()).isEqualTo(header);
  }

  @Test
  void aNonUuidHeaderDerivesTheSameStableValueAsToday() {
    MDC.put(CorrelationIdHolder.MDC_KEY, "request-42");

    // Literal asserted directly so the derivation itself can't silently drift.
    assertThat(CorrelationIds.current())
        .isEqualTo(UUID.fromString("35777df0-3beb-3c8b-9b92-e989871738a7"));
  }

  @Test
  void withNoCorrelationIdPresentTwoSuccessiveCallsProduceTheSameValue() {
    assertThat(CorrelationIdHolder.current()).isNull();

    UUID first = CorrelationIds.current();
    UUID second = CorrelationIds.current();

    assertThat(first).isEqualTo(second);
  }

  @Test
  void ofIsDeterministicForTheSameInput() {
    assertThat(CorrelationIds.of("some-header")).isEqualTo(CorrelationIds.of("some-header"));
    assertThat(CorrelationIds.of(null)).isEqualTo(CorrelationIds.of(null));
  }
}
