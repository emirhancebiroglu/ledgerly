package com.ledgerly.api.document;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start when {@code ledgerly.document.event-broker} names no adapter.
 *
 * <p>Both adapters are selected by {@code @ConditionalOnProperty}, so an unrecognised value
 * matches neither and simply produces no {@link DocumentEventBroker} bean. Before this guard that
 * surfaced as {@code NoSuchBeanDefinitionException} naming the missing bean rather than the bad
 * property — the same gap {@code RateLimiterBackendGuard} closed for the rate limiter (M9.9 T2),
 * closed here the same way now that a second adapter exists to make the failure reachable.
 */
@Configuration
public class DocumentEventBrokerGuard {

  public DocumentEventBrokerGuard(
      ObjectProvider<DocumentEventBroker> brokers,
      @Value("${ledgerly.document.event-broker:redis}") String configuredBackend) {
    if (brokers.getIfAvailable() == null) {
      throw new IllegalStateException(
          "ledgerly.document.event-broker=\"%s\" matches no event broker; expected \"redis\" or \"in-memory\""
              .formatted(configuredBackend));
    }
  }
}
