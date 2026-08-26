package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The behavior every {@link DocumentEventBroker} must exhibit, run against each adapter by a
 * subclass — the same shape as {@code RateLimiterContract}, and for the same reason: an SSE stream
 * must not deliver differently depending on which backend a deployment happens to run.
 */
abstract class DocumentEventBrokerContract {

  protected abstract DocumentEventBroker broker();

  /** Distinct per test so no case can observe another's traffic. */
  private String freshChannel() {
    return "contract-test:" + UUID.randomUUID();
  }

  /**
   * Polls rather than sleeping a fixed interval: Redis delivers asynchronously on its listener
   * executor, so a fixed wait is either flaky or needlessly slow. Deliberately not Awaitility —
   * the project does not depend on it, and one helper is not worth a new test dependency.
   *
   * <p>Asserts order, not just membership. An adapter that dispatches concurrently would reorder
   * payloads, and {@code StreamSession.sendIfNew} drops anything whose id is not greater than the
   * last one sent — so a reordered terminal event does not merely arrive early, it permanently
   * discards the intermediate stages behind it. An order-insensitive assertion here would accept
   * exactly that.
   */
  private void awaitDelivery(List<String> received, String... expected) {
    if (expected.length == 0) {
      throw new IllegalArgumentException(
          "awaitDelivery cannot prove an absence — it would return immediately. "
              + "Assert a non-delivery explicitly after a settling delay instead.");
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && received.size() < expected.length) {
      try {
        TimeUnit.MILLISECONDS.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(received).containsExactly(expected);
  }

  @Test
  void a_subscriber_receives_a_payload_published_to_its_channel() {
    List<String> received = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    try (var ignored = broker().subscribe(channel, received::add)) {
      broker().publish(channel, "hello");
      awaitDelivery(received, "hello");
    }
  }

  /** One channel per document is what keeps one org's stream out of another's. */
  @Test
  void a_subscriber_receives_nothing_from_another_channel() {
    List<String> received = new CopyOnWriteArrayList<>();
    String subscribed = freshChannel();
    String other = freshChannel();

    try (var ignored = broker().subscribe(subscribed, received::add)) {
      broker().publish(other, "not for you");
      broker().publish(subscribed, "for you");
      awaitDelivery(received, "for you");
    }
  }

  @Test
  void every_subscriber_on_one_channel_receives_the_payload() {
    List<String> first = new CopyOnWriteArrayList<>();
    List<String> second = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    try (var ignoredFirst = broker().subscribe(channel, first::add);
        var ignoredSecond = broker().subscribe(channel, second::add)) {
      broker().publish(channel, "broadcast");
      awaitDelivery(first, "broadcast");
      awaitDelivery(second, "broadcast");
    }
  }

  /** An SSE stream that has completed must stop costing anything. */
  @Test
  void a_closed_subscription_receives_nothing_further() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    var subscription = broker().subscribe(channel, received::add);
    broker().publish(channel, "before");
    awaitDelivery(received, "before");
    subscription.close();
    broker().publish(channel, "after");

    // Nothing to await on: assert the absence held after enough time to have delivered.
    TimeUnit.MILLISECONDS.sleep(500);
    assertThat(received).containsExactly("before");
  }

  /**
   * {@code SseEmitter} can fire completion, timeout and error callbacks in combinations that call
   * the same unsubscribe more than once.
   */
  @Test
  void closing_a_subscription_twice_is_harmless() {
    var subscription = broker().subscribe(freshChannel(), payload -> {});
    subscription.close();

    assertThatCode(subscription::close).doesNotThrowAnyException();
  }

  /**
   * One subscriber throwing must not silence the others: on a shared channel that would let a
   * single broken SSE stream blind every other viewer of the same document.
   */
  @Test
  void a_throwing_subscriber_does_not_stop_delivery_to_the_others() {
    List<String> healthy = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    try (var ignoredBroken =
            broker()
                .subscribe(
                    channel,
                    payload -> {
                      throw new IllegalStateException("subscriber blew up");
                    });
        var ignoredHealthy = broker().subscribe(channel, healthy::add)) {
      broker().publish(channel, "still delivered");
      awaitDelivery(healthy, "still delivered");
    }
  }

  /**
   * Ordering is load-bearing, not incidental: SSE ids must arrive ascending or
   * {@code StreamSession.sendIfNew} silently discards the ones that fall behind.
   */
  @Test
  void payloads_arrive_in_the_order_they_were_published() {
    List<String> received = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    try (var ignored = broker().subscribe(channel, received::add)) {
      broker().publish(channel, "first");
      broker().publish(channel, "second");
      broker().publish(channel, "third");
      awaitDelivery(received, "first", "second", "third");
    }
  }

  /**
   * Publish/subscribe here is strictly live: there is no queue and no replay, and callers depend on
   * that — the SSE stream recovers missed events from the durable history in PostgreSQL, not from
   * the broker. An adapter that buffered undelivered payloads would deliver an event twice, once
   * from replay and once from the buffer.
   */
  @Test
  void a_subscriber_receives_nothing_published_before_it_subscribed() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    String channel = freshChannel();

    broker().publish(channel, "published before anyone was listening");
    try (var ignored = broker().subscribe(channel, received::add)) {
      TimeUnit.MILLISECONDS.sleep(500);
      assertThat(received).isEmpty();
    }
  }

  @Test
  void publishing_to_a_channel_with_no_subscribers_is_not_an_error() {
    assertThatCode(() -> broker().publish(freshChannel(), "into the void"))
        .doesNotThrowAnyException();
  }
}
