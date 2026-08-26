package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Covers what {@link DocumentEventBrokerContract} does not: this adapter's internal state, not its
 * observable delivery behavior.
 */
class InMemoryDocumentEventBrokerTest {

  private InMemoryDocumentEventBroker broker() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
    executor.initialize();
    return new InMemoryDocumentEventBroker(executor);
  }

  /**
   * A document's channel is subscribed to for the life of one SSE connection; a busy instance that
   * kept an empty entry per completed stream would grow unbounded over the app's lifetime.
   */
  @Test
  void unsubscribing_the_last_listener_drops_the_channel_entirely() {
    InMemoryDocumentEventBroker broker = broker();
    String channel = "leak-test:" + UUID.randomUUID();

    var subscription = broker.subscribe(channel, payload -> {});
    assertThat(broker.subscribedChannelCount()).isEqualTo(1);

    subscription.close();

    assertThat(broker.subscribedChannelCount()).isZero();
  }

  @Test
  void unsubscribing_one_of_two_listeners_keeps_the_channel_entry() {
    InMemoryDocumentEventBroker broker = broker();
    String channel = "leak-test:" + UUID.randomUUID();

    var first = broker.subscribe(channel, payload -> {});
    broker.subscribe(channel, payload -> {});
    first.close();

    assertThat(broker.subscribedChannelCount()).isEqualTo(1);
  }
}
