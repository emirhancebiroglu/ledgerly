package com.ledgerly.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

  /**
   * Not auto-configured by Spring Boot (only {@code RedisConnectionFactory}/{@code
   * StringRedisTemplate} are). {@link com.ledgerly.api.document.DocumentEventController} adds and
   * removes a listener per SSE connection against this shared container, one per document channel
   * — cheaper than a container per connection.
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
  }
}
