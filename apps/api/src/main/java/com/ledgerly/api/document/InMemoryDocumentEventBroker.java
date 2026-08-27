package com.ledgerly.api.document;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * In-process {@link DocumentEventBroker} for a single-instance deployment, where a subscriber has
 * nothing to reach across instances for: publish and subscribe run in the same process. Selected
 * by {@code ledgerly.document.event-broker=in-memory}; {@link RedisDocumentEventBroker} remains
 * the default, so a deployment running more than one instance keeps the shared fan-out it needs.
 *
 * <p>Dispatch runs on the same bounded executor {@code RedisConfig} provisions for the Redis
 * adapter, for the reason stated there: each dispatch does a blocking SSE socket write, so a burst
 * of concurrent status changes must stay a queue rather than an unbounded-thread incident, and that
 * bound applies to a blocking write regardless of which broker produced it.
 */
@Component
@ConditionalOnProperty(name = "ledgerly.document.event-broker", havingValue = "in-memory")
public class InMemoryDocumentEventBroker implements DocumentEventBroker {

  private final ThreadPoolTaskExecutor executor;
  private final Map<String, Set<Subscriber>> subscribersByChannel = new ConcurrentHashMap<>();

  public InMemoryDocumentEventBroker(
      @Qualifier("documentEventDispatchExecutor") ThreadPoolTaskExecutor executor) {
    this.executor = executor;
  }

  @Override
  public void publish(String channel, String payload) {
    Set<Subscriber> subscribers = subscribersByChannel.get(channel);
    if (subscribers == null || subscribers.isEmpty()) {
      return;
    }
    // Snapshot before dispatch: a listener unsubscribing mid-dispatch (an SSE stream completing
    // while its own event is in flight) must not throw ConcurrentModificationException, and must
    // not affect delivery to the others on this publish.
    for (Subscriber subscriber : List.copyOf(subscribers)) {
      subscriber.deliver(payload);
    }
  }

  @Override
  public Subscription subscribe(String channel, DocumentEventListener listener) {
    Subscriber subscriber = new Subscriber(channel, listener, executor);
    subscribersByChannel
        .computeIfAbsent(channel, ignored -> new CopyOnWriteArraySet<>())
        .add(subscriber);
    return () -> unsubscribe(channel, subscriber);
  }

  private void unsubscribe(String channel, Subscriber subscriber) {
    subscribersByChannel.computeIfPresent(
        channel,
        (ignored, subscribers) -> {
          subscribers.remove(subscriber);
          // Drop the empty set rather than leaving it resident: a document's channel is
          // subscribed to for the life of one SSE connection, so a busy instance would otherwise
          // accumulate one empty set per document ever streamed.
          return subscribers.isEmpty() ? null : subscribers;
        });
  }

  // package-private for InMemoryDocumentEventBrokerTest's leak assertion; production code never
  // needs to observe this.
  int subscribedChannelCount() {
    return subscribersByChannel.size();
  }

  /**
   * One subscription's own {@link OrderedDispatcher}, so two payloads published back to back on
   * the same channel are always delivered to this listener in that order regardless of how the
   * shared executor's threads happen to schedule two independently submitted tasks.
   *
   * <p>Scoped to the subscription rather than the listener instance: the port's contract does not
   * say a listener is used for exactly one subscription, so ordering must not depend on that.
   */
  private static final class Subscriber {
    private final OrderedDispatcher dispatcher;

    private Subscriber(String channel, DocumentEventListener listener, ThreadPoolTaskExecutor executor) {
      this.dispatcher =
          new OrderedDispatcher(listener::onEvent, executor, "channel=" + channel);
    }

    void deliver(String payload) {
      dispatcher.deliver(payload);
    }
  }
}
