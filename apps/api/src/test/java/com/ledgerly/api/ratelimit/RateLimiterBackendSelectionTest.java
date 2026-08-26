package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

/**
 * Pins which adapter each configuration value selects. Without this, a renamed property or a typo
 * in {@code havingValue} would leave a deployment silently on the wrong backend — a single-instance
 * host still paying for a Redis round trip, or worse, a multi-instance host counting locally and
 * handing every instance a full quota.
 */
class RateLimiterBackendSelectionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
          .withUserConfiguration(Adapters.class);

  @Test
  void redis_is_the_default_so_an_existing_deployment_keeps_its_shared_counter() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(RateLimiter.class)).isInstanceOf(RedisRateLimiter.class));
  }

  @Test
  void the_in_memory_backend_is_selected_explicitly() {
    contextRunner
        .withPropertyValues("ledgerly.rate-limit.backend=in-memory")
        .run(
            context ->
                assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(InMemoryRateLimiter.class));
  }

  @Test
  void redis_can_also_be_named_explicitly() {
    contextRunner
        .withPropertyValues("ledgerly.rate-limit.backend=redis")
        .run(
            context ->
                assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(RedisRateLimiter.class));
  }

  /**
   * Exactly one adapter must ever be active: two would make every injection point ambiguous and
   * fail the context, and zero would leave the cost-bearing paths with no limiter at all.
   */
  @Test
  void exactly_one_adapter_is_active_under_every_supported_value() {
    contextRunner.run(context -> assertThat(context.getBeansOfType(RateLimiter.class)).hasSize(1));
    contextRunner
        .withPropertyValues("ledgerly.rate-limit.backend=in-memory")
        .run(context -> assertThat(context.getBeansOfType(RateLimiter.class)).hasSize(1));
  }


  /**
   * A misspelled value matches neither adapter, which would otherwise leave the cost-bearing paths
   * with no limiter at all. {@link RateLimiterBackendGuard} turns that into a refusal to start, so
   * the typo cannot reach production as unlimited paid LLM calls.
   */
  @Test
  void an_unrecognised_backend_value_refuses_to_start() {
    contextRunner
        .withUserConfiguration(RateLimiterBackendGuard.class)
        .withPropertyValues("ledgerly.rate-limit.backend=inmemory")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("matches no rate limiter")
                    .hasMessageContaining("inmemory"));
  }

  @Test
  void the_guard_admits_every_supported_value() {
    contextRunner
        .withUserConfiguration(RateLimiterBackendGuard.class)
        .run(context -> assertThat(context).hasNotFailed());
    contextRunner
        .withUserConfiguration(RateLimiterBackendGuard.class)
        .withPropertyValues("ledgerly.rate-limit.backend=in-memory")
        .run(context -> assertThat(context).hasNotFailed());
  }

  /** {@code @ConditionalOnProperty} compares values case-insensitively, so casing is forgiving. */
  @Test
  void the_backend_value_is_matched_regardless_of_case() {
    contextRunner
        .withPropertyValues("ledgerly.rate-limit.backend=Redis")
        .run(
            context ->
                assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(RedisRateLimiter.class));
  }

  @Import({RedisRateLimiter.class, InMemoryRateLimiter.class})
  static class Adapters {}
}
