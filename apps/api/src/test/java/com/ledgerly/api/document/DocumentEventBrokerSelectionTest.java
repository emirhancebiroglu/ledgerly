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

  /**
   * Documents today's state ahead of T4, which adds the in-process adapter: any value other than
   * {@code redis} currently selects nothing. The consumers inject {@link DocumentEventBroker}
   * directly, so this is a startup failure rather than a silently broker-less application — the
   * same fail-fast shape {@code RateLimiterBackendGuard} enforces for rate limiting. T4 must add
   * its adapter under this property, not a new one.
   */
  @Test
  void any_other_value_selects_no_broker_until_t4_adds_the_in_process_adapter() {
    contextRunner
        .withPropertyValues("ledgerly.document.event-broker=in-memory")
        .run(context -> assertThat(context.getBeansOfType(DocumentEventBroker.class)).isEmpty());
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
  }

  @Import(RedisDocumentEventBroker.class)
  static class Adapters {}
}
