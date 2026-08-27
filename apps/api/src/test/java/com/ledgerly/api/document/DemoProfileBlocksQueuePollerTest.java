package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * {@code DocumentQueuePoller} is {@code @Profile("!demo")} — deliberately absent while the demo
 * seed's one-time run is active, so a poll tick can't race {@link
 * com.ledgerly.api.demo.DemoSeedRunner}'s own extraction replay. That's correct for the seed
 * itself, but the same profile is what production's `render.yaml` sets on every boot
 * (`SPRING_PROFILES_ACTIVE: prod,demo`) — if it's never removed after the initial seed, this
 * poller stays permanently absent and every real (non-seed) upload sits in {@code PENDING}
 * forever with nothing to ever pick it up. That is exactly what happened in production once: no
 * exception, no log line, the upload flow just silently never progresses past "Extracting
 * document data" until the browser's SSE connection times out.
 *
 * <p>This component-scans only the real {@code DocumentQueuePoller} class — reading its actual
 * {@code @Profile} annotation, not a copy of it — with its constructor dependencies supplied as
 * plain Mockito mocks, no database or full application context needed. It proves Spring's real
 * profile evaluation excludes the bean when "demo" is active, the exact mechanism behind the
 * outage. It can't catch "someone left demo on in render.yaml" by itself; see render.yaml's own
 * comment on {@code SPRING_PROFILES_ACTIVE} for the operational half of this fix, and {@link
 * com.ledgerly.api.document.DocumentStatusPipelineIT} / {@link
 * com.ledgerly.api.expense.ExpensePostingPipelineIT} for tests proving the poller actually
 * dispatches work once it does exist.
 */
class DemoProfileBlocksQueuePollerTest {

  @Configuration
  @ComponentScan(
      basePackageClasses = DocumentQueuePoller.class,
      includeFilters =
          @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DocumentQueuePoller.class),
      useDefaultFilters = false)
  static class PollerScanConfig {

    @Bean
    DocumentRepository documentRepository() {
      return Mockito.mock(DocumentRepository.class);
    }

    @Bean
    DocumentStatusTransitions documentStatusTransitions() {
      return Mockito.mock(DocumentStatusTransitions.class);
    }

    @Bean
    DocumentExtractionWorker documentExtractionWorker() {
      return Mockito.mock(DocumentExtractionWorker.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PollerScanConfig.class);

  @Test
  void pollerBeanIsAbsentWhenDemoProfileIsActive() {
    contextRunner
        .withPropertyValues("spring.profiles.active=demo")
        .run(context -> assertThat(context).doesNotHaveBean(DocumentQueuePoller.class));
  }

  @Test
  void pollerBeanIsPresentWithoutTheDemoProfile() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(DocumentQueuePoller.class));
  }
}
