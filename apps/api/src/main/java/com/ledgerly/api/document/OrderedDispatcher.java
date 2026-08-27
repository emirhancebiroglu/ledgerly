package com.ledgerly.api.document;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * One subscription's own FIFO run of a shared executor, so two payloads delivered back to back
 * are always handed to the consumer in that order — regardless of how the shared executor's
 * threads happen to schedule two independently submitted tasks. {@link StreamSession#sendIfNew}
 * drops any event whose id is not greater than the last one delivered, so out-of-order delivery
 * here would silently discard a stage rather than merely delay it.
 *
 * <p>Shared between {@link InMemoryDocumentEventBroker} and {@link RedisDocumentEventBroker}
 * rather than each reimplementing this: {@code RedisMessageListenerContainer} dispatches each
 * incoming message to {@code documentEventDispatchExecutor} (a pooled, multi-thread executor —
 * {@code RedisConfig}, sized for bounded parallelism across *different* channels' blocking SSE
 * writes) as an independent task, with no ordering guarantee between messages published back to
 * back on the same Redis channel; two fast publishes can be dispatched to two different pool
 * threads and complete in either order. Found via a real (not mocked) Redis contract test —
 * {@code payloads_arrive_in_the_order_they_were_published} — failing intermittently with events
 * observed out of order.
 */
final class OrderedDispatcher {

  private static final Logger log = LoggerFactory.getLogger(OrderedDispatcher.class);

  private final Consumer<String> consumer;
  private final ThreadPoolTaskExecutor executor;
  private final Queue<String> pending = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private final String logContext;

  OrderedDispatcher(Consumer<String> consumer, ThreadPoolTaskExecutor executor, String logContext) {
    this.consumer = consumer;
    this.executor = executor;
    this.logContext = logContext;
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
      consumer.accept(payload);
    } catch (RuntimeException e) {
      // One broken subscriber must not affect delivery to the others on the same channel, and
      // dispatch itself is already decoupled from whatever produced the event.
      log.warn(
          "Document event listener threw exceptionType={} {}", e.getClass().getSimpleName(), logContext);
    }
  }
}
