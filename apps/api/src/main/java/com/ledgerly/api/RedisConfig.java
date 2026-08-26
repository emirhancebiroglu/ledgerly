package com.ledgerly.api;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RedisConfig {

  /**
   * Not auto-configured by Spring Boot (only {@code RedisConnectionFactory}/{@code
   * StringRedisTemplate} are). {@link com.ledgerly.api.document.RedisDocumentEventBroker} adds and
   * removes a listener per SSE connection against this shared container, one per document channel
   * — cheaper than a container per connection.
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      @Qualifier("documentEventDispatchExecutor") ThreadPoolTaskExecutor documentEventDispatchExecutor) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(documentEventDispatchExecutor);
    return container;
  }

  /**
   * Bounds document-event dispatch the same way {@link AsyncConfig} bounds document extraction:
   * left on a container's default, Redis dispatch runs on {@code SimpleAsyncTaskExecutor} — one
   * new, unpooled thread per message. Each dispatch does a blocking SSE socket write ({@code
   * DocumentEventBroker.DocumentEventListener#onEvent}), so a burst of concurrent status changes
   * across many open streams would otherwise be an unbounded-thread incident, not a queue.
   *
   * <p>Shared with {@code InMemoryDocumentEventBroker} (M9.9 T4) rather than each adapter owning
   * its own pool: the bound exists because dispatch does a blocking socket write regardless of
   * which broker delivered the event, so the two adapters should be able to run out of the same
   * capacity, not compete for separate ones sized to a guess.
   */
  @Bean
  public ThreadPoolTaskExecutor documentEventDispatchExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(32);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("document-event-dispatch-");
    executor.initialize();
    return executor;
  }
}
