package com.ledgerly.api.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/documents/{id}/events} — ordered SSE replay of durable document activity,
 * with Redis used only to reduce live-delivery latency after the replay catches up.
 *
 * <p>A listener is registered before querying the durable history. Its messages are buffered until
 * that query is sent, so an event committed in the subscription/replay window cannot arrive ahead
 * of an older replay event or be lost in that race. A Redis outage can still defer a live event,
 * but never erases it: the standard {@code Last-Event-ID} reconnection path replays PostgreSQL.
 */
@RestController
public class DocumentEventController {

  private static final Logger log = LoggerFactory.getLogger(DocumentEventController.class);
  private static final String EVENT_NAME = "activity";
  private static final long EMITTER_TIMEOUT_MILLIS = 15 * 60 * 1000L;
  private static final long HEARTBEAT_SECONDS = 20L;

  private final DocumentUploadService documentUploadService;
  private final DocumentActivityService documentActivityService;
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
      DocumentActivityService documentActivityService,
      RedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper) {
    this.documentUploadService = documentUploadService;
    this.documentActivityService = documentActivityService;
    this.listenerContainer = listenerContainer;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/api/v1/documents/{id}/events")
  public SseEmitter events(
      @PathVariable UUID id,
      @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    documentUploadService.findForOrganization(id, principal);
    long afterId = lastEventId == null ? 0L : lastEventId;
    if (afterId < 0) {
      throw new IllegalArgumentException("Last-Event-ID must not be negative");
    }

    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    StreamSession session = new StreamSession(emitter, afterId);
    ChannelTopic topic = new ChannelTopic(DocumentEventChannels.activityChannelFor(id));
    MessageListener listener = (message, pattern) -> onMessage(session, message);

    var heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            session::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    Runnable unsubscribe =
        () -> {
          listenerContainer.removeMessageListener(listener, topic);
          heartbeat.cancel(false);
        };
    emitter.onCompletion(unsubscribe);
    emitter.onTimeout(unsubscribe);
    emitter.onError(throwable -> unsubscribe.run());

    listenerContainer.addMessageListener(listener, topic);
    session.replay(documentActivityService.replay(id, principal.organizationId(), afterId));
    return emitter;
  }

  private void onMessage(StreamSession session, Message message) {
    try {
      session.accept(objectMapper.readValue(message.getBody(), DocumentActivityResponse.class));
    } catch (Exception e) {
      log.warn("Failed to relay document activity event exceptionType={}", e.getClass().getSimpleName());
      session.completeWithError(e);
    }
  }

  private static final class StreamSession {
    private final SseEmitter emitter;
    private final List<DocumentActivityResponse> buffered = new ArrayList<>();
    private long lastSentId;
    private boolean replaying = true;
    private boolean closed;

    private StreamSession(SseEmitter emitter, long lastSentId) {
      this.emitter = emitter;
      this.lastSentId = lastSentId;
    }

    synchronized void replay(List<DocumentActivityResponse> history) {
      history.stream().sorted(Comparator.comparingLong(DocumentActivityResponse::id)).forEach(this::sendIfNew);
      replaying = false;
      buffered.stream().sorted(Comparator.comparingLong(DocumentActivityResponse::id)).forEach(this::sendIfNew);
      buffered.clear();
    }

    synchronized void accept(DocumentActivityResponse activity) {
      if (replaying) {
        buffered.add(activity);
        return;
      }
      sendIfNew(activity);
    }

    synchronized void sendHeartbeat() {
      if (closed) {
        return;
      }
      try {
        emitter.send(SseEmitter.event().comment("keepalive"));
      } catch (IOException e) {
        completeWithError(e);
      }
    }

    synchronized void completeWithError(Throwable error) {
      if (!closed) {
        closed = true;
        emitter.completeWithError(error);
      }
    }

    private void sendIfNew(DocumentActivityResponse activity) {
      if (closed || activity.id() <= lastSentId) {
        return;
      }
      try {
        emitter.send(
            SseEmitter.event()
                .id(Long.toString(activity.id()))
                .name(EVENT_NAME)
                .data(activity));
        lastSentId = activity.id();
        if (activity.stage().isTerminal()) {
          closed = true;
          emitter.complete();
        }
      } catch (IOException e) {
        completeWithError(e);
      }
    }
  }
}
