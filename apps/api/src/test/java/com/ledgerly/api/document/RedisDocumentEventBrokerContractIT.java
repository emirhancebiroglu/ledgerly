package com.ledgerly.api.document;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the shared {@link DocumentEventBrokerContract} against real Redis pub/sub. Mocking it would
 * prove only that the adapter calls the methods it was written to call — not that a message
 * published on one connection reaches a listener registered on another, which is the entire job.
 *
 * <p>Its own container rather than {@code AbstractPostgresIT}'s: this needs no Spring context and
 * no database, and borrowing that base class would start Postgres and boot an application to
 * exercise one class.
 */
@Tag("integration")
class RedisDocumentEventBrokerContractIT extends DocumentEventBrokerContract {

  private static final RedisContainer REDIS =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private static final RedisDocumentEventBroker BROKER;

  static {
    REDIS.start();
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
    BROKER = new RedisDocumentEventBroker(new StringRedisTemplate(connectionFactory), listenerContainer);
  }

  @Override
  protected DocumentEventBroker broker() {
    return BROKER;
  }
}
