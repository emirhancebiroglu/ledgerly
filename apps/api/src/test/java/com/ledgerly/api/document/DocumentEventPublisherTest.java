package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * These publishers run at {@code AFTER_COMMIT}: by the time they execute, the row they describe is
 * durable and the caller's transaction has already succeeded. A broker failure must therefore stay
 * inside the publisher — propagating it would fail an operation that has, in fact, completed.
 */
@ExtendWith(MockitoExtension.class)
class DocumentEventPublisherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private DocumentEventBroker broker;

  @Test
  void a_broker_failure_never_reaches_the_caller_of_the_committed_transaction() {
    doThrow(new IllegalStateException("broker down")).when(broker).publish(anyString(), anyString());
    DocumentEventPublisher publisher = new DocumentEventPublisher(broker, objectMapper);

    assertThatCode(() -> publisher.onDocumentStatusChanged(statusEvent(UUID.randomUUID())))
        .doesNotThrowAnyException();
  }

  /** One channel per document, so a subscriber only ever receives the document it asked about. */
  @Test
  void a_status_event_is_published_to_that_documents_own_channel() {
    UUID documentId = UUID.randomUUID();
    DocumentEventPublisher publisher = new DocumentEventPublisher(broker, objectMapper);
    ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

    publisher.onDocumentStatusChanged(statusEvent(documentId));

    verify(broker).publish(channel.capture(), payload.capture());
    assertThat(channel.getValue()).isEqualTo("document-events:" + documentId);
    assertThat(payload.getValue()).contains(documentId.toString());
  }

  private static DocumentStatusChangedEvent statusEvent(UUID documentId) {
    return new DocumentStatusChangedEvent(
        documentId, UUID.randomUUID(), DocumentStatus.EXTRACTED, null);
  }
}
