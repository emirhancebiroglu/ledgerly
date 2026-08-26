package com.ledgerly.api;

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
   *
   * <p>{@code setTaskExecutor} bounds message dispatch the same way {@link AsyncConfig} bounds
   * document extraction: left on the container's default, dispatch runs on {@code
   * SimpleAsyncTaskExecutor} — one new, unpooled thread per message. Each dispatch here does a
   * blocking SSE socket write ({@code RedisDocumentEventBroker#subscribe}), so a burst of
   * concurrent status changes across many open streams would otherwise be an unbounded-thread
   * incident, not a queue.
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setTaskExecutor(redisListenerExecutor());
    return container;
  }

  private ThreadPoolTaskExecutor redisListenerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(32);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("redis-listener-");
    executor.initialize();
    return executor;
  }
}
