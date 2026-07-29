package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Relays already-committed durable activity to Redis without making Redis part of correctness. */
@Component
public class DocumentActivityEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DocumentActivityEventPublisher.class);

  private final DocumentActivityRepository repository;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public DocumentActivityEventPublisher(
      DocumentActivityRepository repository,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onActivityRecorded(DocumentActivityRecordedEvent event) {
    try {
      DocumentActivity activity = repository.findById(event.activityId()).orElse(null);
      if (activity == null) {
        return;
      }
      redisTemplate.convertAndSend(
          DocumentEventChannels.activityChannelFor(event.documentId()),
          objectMapper.writeValueAsString(DocumentActivityResponse.from(activity)));
    } catch (Exception e) {
      log.warn(
          "Failed to publish document activity event documentId={} exceptionType={}",
          event.documentId(),
          e.getClass().getSimpleName());
    }
  }
}
