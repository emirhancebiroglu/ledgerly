package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

  private static final String EVENT_NAME = "status";

  /** No event is expected to sit unconsumed for longer than this; a stalled client's connection
   * closes rather than holding server resources forever. */
  private static final long EMITTER_TIMEOUT_MILLIS = 15 * 60 * 1000L;

  /**
   * Registering the three {@code SseEmitter} callbacks only detects a dropped client the next
   * time something is written to it — with no traffic, a half-open connection (client killed,
   * laptop closed, an idle-timing-out proxy) goes unnoticed until {@link #EMITTER_TIMEOUT_MILLIS}.
   * A periodic comment-line ping forces that write, so a dead peer is detected promptly instead of
   * silently holding a listener for up to 15 minutes, and keeps proxies that kill idle connections
   * from severing a still-live stream during a slow extraction.
   */
  private static final long HEARTBEAT_SECONDS = 20L;

  private final DocumentUploadService documentUploadService;
  private final RedisMessageListenerContainer listenerContainer;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService heartbeatScheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

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
    MessageListener listener = (message, pattern) -> onMessage(emitter, message);

    var heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            () -> sendHeartbeat(emitter), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

    Runnable unsubscribe =
        () -> {
          listenerContainer.removeMessageListener(listener, topic);
          heartbeat.cancel(false);
        };
    emitter.onCompletion(unsubscribe);
    emitter.onTimeout(unsubscribe);
    emitter.onError(throwable -> unsubscribe.run());

    listenerContainer.addMessageListener(listener, topic);

    if (document.getStatus().isTerminal()) {
      // The transition to a terminal status may have already happened and published before this
      // subscription existed -- emit the current state immediately rather than leaving a client
      // waiting forever for an event that already fired.
      send(
          emitter,
          new DocumentStatusChangedEvent(
              document.getId(), document.getOrganizationId(), document.getStatus(), document.getFailureReason()),
          true);
    }

    return emitter;
  }

  private void onMessage(SseEmitter emitter, Message message) {
    try {
      DocumentStatusChangedEvent event =
          objectMapper.readValue(message.getBody(), DocumentStatusChangedEvent.class);
      send(emitter, event, event.status().isTerminal());
    } catch (Exception e) {
      log.warn("Failed to relay document status event to SSE client: {}", e.toString());
      emitter.completeWithError(e);
    }
  }

  private void send(SseEmitter emitter, DocumentStatusChangedEvent event, boolean thenComplete) {
    try {
      emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
      if (thenComplete) {
        emitter.complete();
      }
    } catch (IOException e) {
      // The client is gone (broken pipe, navigated away) -- complete rather than leaving a
      // listener registered against a connection nothing will ever read from again.
      emitter.completeWithError(e);
    }
  }

  private void sendHeartbeat(SseEmitter emitter) {
    try {
      emitter.send(SseEmitter.event().comment("keepalive"));
    } catch (IOException e) {
      emitter.completeWithError(e);
    }
  }
}
