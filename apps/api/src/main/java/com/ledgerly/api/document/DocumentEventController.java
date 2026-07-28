package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/documents/{id}/events} — SSE stream of {@link DocumentStatusChangedEvent}s
 * for one document, backed by the Redis channel {@link DocumentEventPublisher} publishes to. The
 * expense-detail screen's agent-activity panel is the client (architecture.md §2).
 *
 * <p>Terminates the stream (rather than leaving it open indefinitely) once a terminal status is
 * observed, since {@link DocumentStatus#isTerminal()} means no further event will ever arrive on
 * this document's channel.
 */
@RestController
public class DocumentEventController {

  private static final Logger log = LoggerFactory.getLogger(DocumentEventController.class);

  /** No event is expected to sit unconsumed for longer than this; a stalled client's connection
   * closes rather than holding server resources forever. */
  private static final long EMITTER_TIMEOUT_MILLIS = 15 * 60 * 1000L;

  private final DocumentUploadService documentUploadService;
  private final RedisMessageListenerContainer listenerContainer;
  private final ObjectMapper objectMapper;

  public DocumentEventController(
      DocumentUploadService documentUploadService,
      RedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper) {
    this.documentUploadService = documentUploadService;
    this.listenerContainer = listenerContainer;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/api/v1/documents/{id}/events")
  public SseEmitter events(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    // Org-scoped lookup before any subscription is registered: a cross-org id 404s here, and
    // nothing about this document's channel is ever exposed to a caller who shouldn't see it.
    Document document = documentUploadService.findForOrganization(id, principal);

    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    ChannelTopic topic = new ChannelTopic(DocumentEventChannels.channelFor(id));
    MessageListener listener = (message, pattern) -> onMessage(emitter, listenerContainer, topic, message);

    Runnable unsubscribe = () -> listenerContainer.removeMessageListener(listener, topic);
    emitter.onCompletion(unsubscribe);
    emitter.onTimeout(unsubscribe);
    emitter.onError(throwable -> unsubscribe.run());

    listenerContainer.addMessageListener(listener, topic);

    if (document.getStatus().isTerminal()) {
      // The transition to a terminal status may have already happened and published before this
      // subscription existed -- emit the current state immediately rather than leaving a client
      // waiting forever for an event that already fired.
      emitCurrentStatus(emitter, document);
    }

    return emitter;
  }

  private void onMessage(
      SseEmitter emitter,
      RedisMessageListenerContainer listenerContainer,
      ChannelTopic topic,
      Message message) {
    try {
      DocumentStatusChangedEvent event =
          objectMapper.readValue(message.getBody(), DocumentStatusChangedEvent.class);
      emitter.send(SseEmitter.event().name("status").data(event));
      if (event.status().isTerminal()) {
        emitter.complete();
      }
    } catch (IOException e) {
      // The client is gone (broken pipe, navigated away) -- complete rather than leaving a
      // listener registered against a connection nothing will ever read from again.
      emitter.completeWithError(e);
    } catch (Exception e) {
      log.warn("Failed to relay document status event to SSE client: {}", e.toString());
      emitter.completeWithError(e);
    }
  }

  private void emitCurrentStatus(SseEmitter emitter, Document document) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("status")
              .data(
                  new DocumentStatusChangedEvent(
                      document.getId(),
                      document.getOrganizationId(),
                      document.getStatus(),
                      document.getFailureReason())));
      emitter.complete();
    } catch (IOException e) {
      emitter.completeWithError(e);
    }
  }
}
