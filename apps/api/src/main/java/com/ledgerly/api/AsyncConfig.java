package com.ledgerly.api;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Document extraction runs off the request thread here. A bounded pool is deliberate — Spring's
 * default {@code SimpleAsyncTaskExecutor} creates one thread per task with no ceiling, so a burst
 * of uploads would be an unbounded-thread-creation incident instead of a queue.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

  public static final String DOCUMENT_PROCESSING_EXECUTOR = "documentProcessingExecutor";

  @Bean(DOCUMENT_PROCESSING_EXECUTOR)
  public Executor documentProcessingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("doc-extract-");
    executor.initialize();
    return executor;
  }

  @Override
  public Executor getAsyncExecutor() {
    return documentProcessingExecutor();
  }
}
