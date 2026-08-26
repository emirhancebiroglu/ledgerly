package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@link DocumentEventBrokerContract#payloads_arrive_in_the_order_they_were_published} passes with
 * a default 4-32 thread pool regardless of whether ordering is actually enforced, because three
 * payloads rarely land on different threads by chance. This drives many more payloads through a
 * deliberately small, saturated pool — the shape most likely to interleave independently submitted
 * tasks — to demonstrate the per-subscriber sequential run in {@code InMemoryDocumentEventBroker}
 * is load-bearing, not incidental.
 */
class InMemoryDocumentEventBrokerOrderingStressTest {

  @RepeatedTest(20)
  void publish_order_survives_a_saturated_small_pool() throws InterruptedException {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(1000);
    executor.initialize();
    InMemoryDocumentEventBroker broker = new InMemoryDocumentEventBroker(executor);

    List<String> received = new CopyOnWriteArrayList<>();
    String channel = "stress-" + UUID.randomUUID();
    int payloadCount = 200;
    CountDownLatch delivered = new CountDownLatch(payloadCount);

    try (var subscription =
        broker.subscribe(
            channel,
            payload -> {
              received.add(payload);
              delivered.countDown();
            })) {
      // A second, busy channel competing for the same two-thread pool is what actually stresses
      // scheduling — a single quiet subscriber rarely gets interleaved by the executor at all.
      try (ExecutorService publishers = Executors.newFixedThreadPool(4)) {
        publishers.execute(
            () -> {
              for (int i = 0; i < payloadCount; i++) {
                broker.publish(channel, Integer.toString(i));
              }
            });
        for (int competitor = 0; competitor < 3; competitor++) {
          String competitorChannel = "stress-competitor-" + competitor;
          broker.subscribe(competitorChannel, payload -> {});
          publishers.execute(
              () -> {
                for (int i = 0; i < payloadCount; i++) {
                  broker.publish(competitorChannel, Integer.toString(i));
                }
              });
        }
      }

      assertThat(delivered.await(10, TimeUnit.SECONDS)).as("all payloads delivered").isTrue();
    } finally {
      executor.shutdown();
    }

    List<String> expected =
        java.util.stream.IntStream.range(0, payloadCount)
            .mapToObj(Integer::toString)
            .collect(java.util.stream.Collectors.toList());
    assertThat(received).containsExactlyElementsOf(expected);
  }
}
