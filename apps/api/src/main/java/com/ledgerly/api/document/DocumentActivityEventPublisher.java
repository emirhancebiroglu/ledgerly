package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays already-committed durable activity to the event broker without making delivery part of
 * correctness: the row is persisted first, and a subscriber that misses the notification still sees
 * it by replaying from {@code Last-Event-ID}.
 */
@Component
public class DocumentActivityEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DocumentActivityEventPublisher.class);

  private final DocumentActivityRepository repository;
  private final DocumentEventBroker broker;
  private final ObjectMapper objectMapper;

  public DocumentActivityEventPublisher(
      DocumentActivityRepository repository,
      DocumentEventBroker broker,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.broker = broker;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onActivityRecorded(DocumentActivityRecordedEvent event) {
    try {
      DocumentActivity activity = repository.findById(event.activityId()).orElse(null);
      if (activity == null) {
        return;
      }
      broker.publish(
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
