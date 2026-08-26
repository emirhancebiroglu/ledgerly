package com.ledgerly.api.document;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub {@link DocumentEventBroker} — the implementation that makes an SSE stream work when
 * the instance serving it is not the instance that processed the document.
 *
 * <p>Listeners are added to and removed from one shared {@link RedisMessageListenerContainer}
 * rather than a container per stream, which is what {@code RedisConfig} already provisions and
 * bounds with its own executor.
 */
@Component
@ConditionalOnProperty(
    name = "ledgerly.document.event-broker",
    havingValue = "redis",
    matchIfMissing = true)
public class RedisDocumentEventBroker implements DocumentEventBroker {

  private final StringRedisTemplate redisTemplate;
  private final RedisMessageListenerContainer listenerContainer;

  public RedisDocumentEventBroker(
      StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
  }

  @Override
  public void publish(String channel, String payload) {
    redisTemplate.convertAndSend(channel, payload);
  }

  @Override
  public Subscription subscribe(String channel, DocumentEventListener listener) {
    ChannelTopic topic = new ChannelTopic(channel);
    // Decoding the body here is what keeps the Redis Message type out of the port: callers receive
    // the payload they published, not a driver object they would have to know how to unwrap.
    MessageListener redisListener =
        (message, pattern) ->
            listener.onEvent(new String(message.getBody(), StandardCharsets.UTF_8));
    listenerContainer.addMessageListener(redisListener, topic);
    return () -> listenerContainer.removeMessageListener(redisListener, topic);
  }
}
