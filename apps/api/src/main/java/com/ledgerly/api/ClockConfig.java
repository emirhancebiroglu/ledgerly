package com.ledgerly.api;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  /**
   * Injected rather than called statically so date-sensitive rules — an invoice dated too far in
   * the future, or implausibly far in the past — can be tested at a fixed instant instead of
   * against whatever today happens to be.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
