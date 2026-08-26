package com.ledgerly.api.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-process {@link RateLimiter} for a single-instance deployment, where the counter has nothing to
 * coordinate with: one instance enforcing a quota locally admits exactly the same requests a shared
 * counter would. Selected by {@code ledgerly.rate-limit.backend=in-memory}; {@link
 * RedisRateLimiter} remains the default, so any deployment running more than one instance keeps the
 * shared counter it needs.
 *
 * <p>Time comes from {@link System#nanoTime()} rather than the wall clock: an NTP correction or a
 * DST shift must not extend a window into a lockout or collapse one into a free pass.
 *
 * <p>The key space is unbounded by nature — one key per organization, one per email fingerprint —
 * so expired windows are evicted rather than accumulated. Eviction is opportunistic: every {@value
 * #CALLS_BETWEEN_SWEEPS} calls, one sweep drops every window that has lapsed. That bounds retention
 * rather than eliminating it — a burst of one-off keys that stops before the next sweep stays
 * resident until traffic resumes, so at most one sweep interval's worth of stale keys is held.
 * Chosen over a background thread deliberately: a scheduled sweep that silently died would leak
 * without any signal, whereas this cannot run less often than the traffic that creates the keys.
 */
@Component
@ConditionalOnProperty(name = "ledgerly.rate-limit.backend", havingValue = "in-memory")
public class InMemoryRateLimiter implements RateLimiter {

  /**
   * Sweeping every key on every call would make each request O(keys). Sweeping never would let a
   * burst of one-off keys (a credential-stuffing run against many addresses) hold memory until
   * restart. Amortizing the sweep across calls bounds both.
   */
  private static final int CALLS_BETWEEN_SWEEPS = 1000;

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicInteger callsSinceSweep = new AtomicInteger();

  @Override
  public long acquire(String key, int maxRequests, long windowSeconds) {
    long now = System.nanoTime();
    long windowNanos = Duration.ofSeconds(windowSeconds).toNanos();

    if (callsSinceSweep.incrementAndGet() >= CALLS_BETWEEN_SWEEPS) {
      callsSinceSweep.set(0);
      windows.values().removeIf(window -> window.hasExpired(now));
    }

    // compute() holds the bin lock for this key, so read-modify-write of one window is atomic
    // against concurrent callers — the property that makes the Redis adapter's Lua script atomic,
    // reproduced with the same guarantee rather than an approximation of it.
    Window window =
        windows.compute(
            key,
            (ignored, existing) ->
                existing == null || existing.hasExpired(now)
                    ? Window.startingAt(now, windowNanos)
                    : existing.withOneMoreRequest());

    long remainingSeconds = window.remainingSeconds(now);
    return window.count() > maxRequests ? -remainingSeconds : remainingSeconds;
  }

  /** One fixed window: when it opened, when it closes, and how many attempts landed inside it. */
  private record Window(long startNanos, long windowNanos, int count) {

    static Window startingAt(long now, long windowNanos) {
      return new Window(now, windowNanos, 1);
    }

    Window withOneMoreRequest() {
      return new Window(startNanos, windowNanos, count + 1);
    }

    boolean hasExpired(long now) {
      // Subtraction, not comparison: nanoTime() may be negative and can wrap, and only the
      // difference between two readings is meaningful.
      return now - startNanos >= windowNanos;
    }

    /**
     * Rounded up so a caller is never told to retry before the window actually closes. Floored at
     * 1 for the same reason: a truthful "0 seconds" reads as "retry immediately", and worse, a
     * negated zero is not negative, so a caller checking the sign would admit the request. {@link
     * RedisRateLimiter} reads {@code PTTL} and applies this identical rule, which is what keeps
     * the two backends returning the same value for the same moment in a window.
     */
    long remainingSeconds(long now) {
      long remainingNanos = windowNanos - (now - startNanos);
      return Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    }
  }
}
