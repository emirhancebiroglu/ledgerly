package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays a committed {@link DocumentStatusChangedEvent} to the event broker, one channel per
 * document id ({@link DocumentEventChannels#statusChannelFor}), so {@code GET
 * /documents/{id}/events} (M7a T6) can subscribe without polling the database.
 *
 * <p>{@code AFTER_COMMIT} rather than the default phase: firing on the Spring event before the
 * enclosing transaction commits would let a subscriber react to a status the database hasn't
 * actually reached yet, and could still roll back.
 */
@Component
public class DocumentEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DocumentEventPublisher.class);

  private final DocumentEventBroker broker;
  private final ObjectMapper objectMapper;

  public DocumentEventPublisher(DocumentEventBroker broker, ObjectMapper objectMapper) {
    this.broker = broker;
    this.objectMapper = objectMapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDocumentStatusChanged(DocumentStatusChangedEvent event) {
    String channel = DocumentEventChannels.statusChannelFor(event.documentId());
    try {
      broker.publish(channel, objectMapper.writeValueAsString(event));
    } catch (Exception e) {
      // A dropped notification is not a lost status: the row is already committed, and a client
      // reconnecting or polling GET /documents/{id} still sees it. Never let a broker hiccup
      // affect a caller of the @Transactional method that already committed successfully.
      log.warn(
          "Failed to publish document status event documentId={} exceptionType={}",
          event.documentId(),
          e.getClass().getSimpleName());
    }
  }
}
