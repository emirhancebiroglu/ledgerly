package com.ledgerly.api.document;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(InMemoryDocumentEventBroker.class);

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
   * One subscription's own FIFO run of the executor, so two payloads published back to back on the
   * same channel are always delivered to this listener in that order — regardless of how the
   * shared executor's threads happen to schedule two independently submitted tasks. {@link
   * StreamSession#sendIfNew} drops any event whose id is not greater than the last one delivered,
   * so out-of-order delivery here would silently discard a stage rather than merely delay it.
   *
   * <p>Scoped to the subscription rather than the listener instance: the port's contract does not
   * say a listener is used for exactly one subscription, so ordering must not depend on that.
   */
  private static final class Subscriber {
    private final DocumentEventListener listener;
    private final ThreadPoolTaskExecutor executor;
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final Logger log = LoggerFactory.getLogger(Subscriber.class);
    private final String channel;

    private Subscriber(String channel, DocumentEventListener listener, ThreadPoolTaskExecutor executor) {
      this.channel = channel;
      this.listener = listener;
      this.executor = executor;
    }

    void deliver(String payload) {
      pending.add(payload);
      scheduleDrainIfIdle();
    }

    private void scheduleDrainIfIdle() {
      // Only the caller that flips false->true schedules a drain; everyone else's payload is
      // already guaranteed to be picked up by that drain's loop, since it re-checks the queue
      // before releasing the flag. This is what keeps delivery single-threaded per subscriber
      // without holding a lock across the blocking SSE write in deliverOne.
      if (draining.compareAndSet(false, true)) {
        executor.execute(this::drain);
      }
    }

    /**
     * The release-then-recheck shape below (rather than checking {@code pending} inside the loop
     * and releasing only once it is empty) closes a real race: a payload can be queued by another
     * thread strictly between this drain's last {@code poll()} returning {@code null} and {@code
     * draining} being cleared. Left for "whoever queued it" to notice, two drains could believe
     * themselves the sole owner at once — the exact single-threaded-per-subscriber property this
     * class exists to guarantee. Looping rather than recursing after the recheck keeps this
     * bounded under sustained traffic instead of growing one stack frame per re-acquisition.
     */
    private void drain() {
      do {
        try {
          String payload;
          while ((payload = pending.poll()) != null) {
            deliverOne(payload);
          }
        } finally {
          draining.set(false);
        }
      } while (!pending.isEmpty() && draining.compareAndSet(false, true));
    }

    private void deliverOne(String payload) {
      try {
        listener.onEvent(payload);
      } catch (RuntimeException e) {
        // Mirrors DocumentEventPublisher's own swallow-and-log: one broken subscriber must not
        // affect delivery to the others on the same channel, and dispatch itself is already
        // decoupled from the publishing transaction.
        log.warn(
            "Document event listener threw exceptionType={} channel={}",
            e.getClass().getSimpleName(),
            channel);
      }
    }
  }
}
