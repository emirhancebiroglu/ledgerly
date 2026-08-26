package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The behavior every {@link RateLimiter} must exhibit, run against each adapter by a subclass.
 *
 * <p>Written as one shared contract rather than two parallel test classes because the point of the
 * port is that a deployment can swap adapters without changing what a caller observes. Two
 * independently written suites would drift, and the drift would be exactly the bug this milestone
 * exists to avoid — a limiter that admits a different number of requests depending on where it is
 * deployed.
 */
abstract class RateLimiterContract {

  protected abstract RateLimiter limiter();

  /** Distinct per test so cases cannot inherit another's counter, in Redis or in a live map. */
  private String freshKey() {
    return "contract-test:" + UUID.randomUUID();
  }

  @Test
  void requests_within_the_quota_are_admitted() {
    RateLimiter limiter = limiter();
    String key = freshKey();

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(limiter.acquire(key, 3, 60))
          .as("attempt %d of a quota of 3", attempt)
          .isPositive();
    }
  }

  @Test
  void the_attempt_after_the_quota_is_rejected() {
    RateLimiter limiter = limiter();
    String key = freshKey();
    for (int attempt = 1; attempt <= 3; attempt++) {
      limiter.acquire(key, 3, 60);
    }

    assertThat(limiter.acquire(key, 3, 60)).isNegative();
  }

  /**
   * The sign carries the verdict and the magnitude carries the retry-after, so a caller can build
   * a {@code Retry-After} header from one return value. Both adapters must agree on both halves.
   */
  /**
   * Asserted as an exact value, not a range. A range wide enough to accept both adapters is wide
   * enough to hide the disagreement between them, which is the one thing this contract exists to
   * prevent: the return value becomes a {@code Retry-After} header, so a loose assertion means the
   * same client on the same quota can be told two different things depending on where it is
   * deployed.
   */
  @Test
  void a_rejection_reports_the_windows_remaining_seconds() {
    RateLimiter limiter = limiter();
    String key = freshKey();
    long admitted = limiter.acquire(key, 1, 60);

    long rejected = limiter.acquire(key, 1, 60);

    assertThat(admitted).as("first attempt, at the top of a 60s window").isEqualTo(60L);
    assertThat(rejected).as("second attempt, still at the top of the window").isEqualTo(-60L);
  }

  /**
   * The last fraction of a window is still inside it. Redis {@code TTL} reports whole seconds, so
   * an adapter that negates it directly returns {@code -0} — which is {@code 0}, and {@code 0} is
   * not negative, so the caller admits the request. That is one free attempt past the quota at the
   * end of every window, on a path that spends money.
   */
  @Test
  void a_rejection_in_the_final_second_of_a_window_is_still_a_rejection()
      throws InterruptedException {
    RateLimiter limiter = limiter();
    String key = freshKey();
    limiter.acquire(key, 1, 10);

    // Into the final second of a 10s window, where a TTL reported in whole seconds reads 0.
    TimeUnit.MILLISECONDS.sleep(9_500);

    assertThat(limiter.acquire(key, 1, 10))
        .as("attempt with under a second left in a 10s window")
        .isNegative();
  }

  /**
   * Pins the rounding rule itself, mid-window, where the two adapters previously disagreed by a
   * second: Redis floored a whole-second {@code TTL} while the in-process window rounded up. Both
   * now round up, so a client is never told to retry before the window has actually closed.
   */
  @Test
  void a_mid_window_rejection_rounds_the_remaining_seconds_up() throws InterruptedException {
    RateLimiter limiter = limiter();
    String key = freshKey();
    limiter.acquire(key, 1, 10);

    TimeUnit.MILLISECONDS.sleep(1_400);

    // 8.6s remain: rounding up reports 9, flooring reports 8.
    assertThat(limiter.acquire(key, 1, 10))
        .as("rejection 1.4s into a 10s window")
        .isEqualTo(-9L);
  }

  @Test
  void separate_keys_hold_separate_quotas() {
    RateLimiter limiter = limiter();
    String exhausted = freshKey();
    String untouched = freshKey();
    limiter.acquire(exhausted, 1, 60);
    limiter.acquire(exhausted, 1, 60);

    assertThat(limiter.acquire(untouched, 1, 60)).isPositive();
  }

  @Test
  void a_window_resets_once_it_expires() throws InterruptedException {
    RateLimiter limiter = limiter();
    String key = freshKey();
    limiter.acquire(key, 1, 1);
    assertThat(limiter.acquire(key, 1, 1)).as("second attempt inside a 1s window").isNegative();

    // Redis TTL has whole-second granularity, so a 1s window can need marginally longer than 1s
    // to actually lapse; this waits past that rather than racing it.
    TimeUnit.MILLISECONDS.sleep(1_500);

    assertThat(limiter.acquire(key, 1, 1)).as("first attempt in the next window").isPositive();
  }

  /**
   * The quota is the whole point: if concurrent callers can interleave a read and a write, a
   * limiter admits more than it was configured to. Both adapters must be atomic per key — Redis
   * through its script, the in-process one through the map's per-bin locking.
   */
  @Test
  void concurrent_attempts_admit_exactly_the_quota() throws InterruptedException {
    RateLimiter limiter = limiter();
    String key = freshKey();
    int maxRequests = 10;
    int attempts = maxRequests + 50;
    AtomicInteger admitted = new AtomicInteger();
    CountDownLatch startLine = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(attempts);

    try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
      for (int attempt = 0; attempt < attempts; attempt++) {
        pool.execute(
            () -> {
              try {
                startLine.await();
                if (limiter.acquire(key, maxRequests, 60) >= 0) {
                  admitted.incrementAndGet();
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }
      startLine.countDown();
      assertThat(finished.await(30, TimeUnit.SECONDS)).as("all attempts completed").isTrue();
    }

    assertThat(admitted).hasValue(maxRequests);
  }
}
