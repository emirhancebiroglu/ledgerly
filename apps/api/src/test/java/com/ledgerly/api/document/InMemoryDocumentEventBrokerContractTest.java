package com.ledgerly.api.document;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Runs the shared {@link DocumentEventBrokerContract} against the in-process adapter. */
class InMemoryDocumentEventBrokerContractTest extends DocumentEventBrokerContract {

  private static final InMemoryDocumentEventBroker BROKER;

  static {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(32);
    executor.setQueueCapacity(500);
    executor.initialize();
    BROKER = new InMemoryDocumentEventBroker(executor);
  }

  @Override
  protected DocumentEventBroker broker() {
    return BROKER;
  }
}
