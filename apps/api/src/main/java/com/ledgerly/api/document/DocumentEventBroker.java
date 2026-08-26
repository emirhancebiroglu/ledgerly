package com.ledgerly.api.document;

/**
 * Carries an already-committed document event from the thread that recorded it to whatever SSE
 * streams are watching that document.
 *
 * <p>Extracted at M9.9 because the only implementation was Redis pub/sub, which exists to reach a
 * subscriber attached to a *different* instance. A single-instance deployment has no such
 * subscriber, so the same delivery is achievable in-process — and the free-tier host this project
 * deploys to offers no managed Redis.
 *
 * <p>Delivery is best-effort by design, and callers must keep it that way. Every event published
 * here describes a row that is already committed: a subscriber that misses one still sees the
 * truth by reconnecting (the stream replays from {@code Last-Event-ID}) or by reading the document
 * directly. A broker failure must therefore never propagate into the transaction that produced the
 * event, and must never be retried at the cost of the caller.
 */
public interface DocumentEventBroker {

  /**
   * Publishes {@code payload} to everyone currently subscribed to {@code channel}.
   *
   * <p>Implementations deliver to subscribers that exist at this moment; there is no queue and no
   * replay. Callers are expected to have committed the underlying row first.
   */
  void publish(String channel, String payload);

  /**
   * Registers {@code listener} for every payload published to {@code channel} until the returned
   * registration is closed.
   *
   * <p>The registration is the only way to unsubscribe, which keeps the caller from having to hold
   * both the listener and the channel to undo what it did — a shape that previously let a closed
   * SSE stream leak its listener if either half was forgotten.
   */
  Subscription subscribe(String channel, DocumentEventListener listener);

  /** Receives one payload published to a subscribed channel. */
  @FunctionalInterface
  interface DocumentEventListener {
    void onEvent(String payload);
  }

  /**
   * A live subscription. {@link #close()} must be idempotent: an SSE emitter can complete, time out
   * and error in ways that fire more than one of its callbacks.
   */
  @FunctionalInterface
  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }
}
