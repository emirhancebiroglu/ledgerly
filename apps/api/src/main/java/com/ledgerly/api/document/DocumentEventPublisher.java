package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays a committed {@link DocumentStatusChangedEvent} to Redis, one channel per document id
 * ({@link DocumentEventChannels#channelFor}), so {@code GET /documents/{id}/events} (M7a T6) can
 * subscribe without polling the database.
 *
 * <p>{@code AFTER_COMMIT} rather than the default phase: firing on the Spring event before the
 * enclosing transaction commits would let a subscriber react to a status the database hasn't
 * actually reached yet, and could still roll back.
 */
@Component
public class DocumentEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DocumentEventPublisher.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public DocumentEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDocumentStatusChanged(DocumentStatusChangedEvent event) {
    String channel = DocumentEventChannels.channelFor(event.documentId());
    try {
      redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
    } catch (Exception e) {
      // A dropped notification is not a lost status: the row is already committed, and a client
      // reconnecting or polling GET /documents/{id} still sees it. Never let a Redis hiccup
      // affect a caller of the @Transactional method that already committed successfully.
      log.warn("Failed to publish document status event for {}: {}", event.documentId(), e.toString());
    }
  }
}
