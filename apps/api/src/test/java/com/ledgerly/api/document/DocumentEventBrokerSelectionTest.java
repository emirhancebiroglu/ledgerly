package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pins which adapter each configuration value selects, mirroring {@code
 * RateLimiterBackendSelectionTest}. Without this, a renamed property or a typo in {@code
 * havingValue} would leave a deployment with no broker and no signal until the first SSE request.
 */
class DocumentEventBrokerSelectionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(RedisDoubles.class, Adapters.class);

  @Test
  void redis_is_the_default_so_an_existing_deployment_keeps_its_cross_instance_fan_out() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(DocumentEventBroker.class))
                .isInstanceOf(RedisDocumentEventBroker.class));
  }

  @Test
  void redis_can_also_be_named_explicitly() {
    contextRunner
        .withPropertyValues("ledgerly.document.event-broker=redis")
        .run(
            context ->
                assertThat(context.getBean(DocumentEventBroker.class))
                    .isInstanceOf(RedisDocumentEventBroker.class));
  }

  @Test
  void the_in_process_backend_is_selected_explicitly() {
    contextRunner
        .withPropertyValues("ledgerly.document.event-broker=in-memory")
        .run(
            context ->
                assertThat(context.getBean(DocumentEventBroker.class))
                    .isInstanceOf(InMemoryDocumentEventBroker.class));
  }

  /**
   * Exactly one adapter must ever be active: two would make every injection point ambiguous and
   * fail the context, and zero would leave the SSE and publisher paths with no broker at all.
   */
  @Test
  void exactly_one_adapter_is_active_under_every_supported_value() {
    contextRunner.run(
        context -> assertThat(context.getBeansOfType(DocumentEventBroker.class)).hasSize(1));
    contextRunner
        .withPropertyValues("ledgerly.document.event-broker=in-memory")
        .run(
            context -> assertThat(context.getBeansOfType(DocumentEventBroker.class)).hasSize(1));
  }

  /**
   * An unrecognised value matches neither {@code @ConditionalOnProperty}, which would otherwise
   * leave the SSE and publisher paths with no broker at all. {@link DocumentEventBrokerGuard}
   * turns that into a refusal to start, mirroring {@code RateLimiterBackendGuard} for the rate
   * limiter (M9.9 T2).
   */
  @Test
  void an_unrecognised_backend_value_refuses_to_start() {
    contextRunner
        .withUserConfiguration(DocumentEventBrokerGuard.class)
        .withPropertyValues("ledgerly.document.event-broker=inmemory")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("matches no event broker")
                    .hasMessageContaining("inmemory"));
  }

  @Test
  void the_guard_admits_every_supported_value() {
    contextRunner
        .withUserConfiguration(DocumentEventBrokerGuard.class)
        .run(context -> assertThat(context).hasNotFailed());
    contextRunner
        .withUserConfiguration(DocumentEventBrokerGuard.class)
        .withPropertyValues("ledgerly.document.event-broker=in-memory")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Configuration(proxyBeanMethods = false)
  static class RedisDoubles {
    @Bean
    StringRedisTemplate stringRedisTemplate() {
      return Mockito.mock(StringRedisTemplate.class);
    }

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer() {
      return Mockito.mock(RedisMessageListenerContainer.class);
    }

    @Bean(name = "documentEventDispatchExecutor")
    ThreadPoolTaskExecutor documentEventDispatchExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.initialize();
      return executor;
    }
  }

  @Import({RedisDocumentEventBroker.class, InMemoryDocumentEventBroker.class})
  static class Adapters {}
}
