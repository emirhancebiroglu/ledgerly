package com.ledgerly.api.document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispatches durable extraction work without tying upload availability to the agent. A candidate
 * scan is intentionally not a lock: every selected row is claimed by a conditional UPDATE, so
 * concurrent pollers can harmlessly see the same row but only one can dispatch it.
 *
 * <p>Disabled on the {@code demo} profile: {@link com.ledgerly.api.demo.DemoSeedRunner} writes
 * its own (fixture-recorded, LLM-free) extraction outcome for each seeded document between
 * upload and {@code markProcessing} — a poll tick landing in that window would otherwise race
 * a real {@code ai} call against the seed and abort it via the status transition guard.
 */
@Component
@Profile("!demo")
public class DocumentQueuePoller {

  private final DocumentRepository documentRepository;
  private final DocumentStatusTransitions transitions;
  private final DocumentExtractionWorker worker;
  private final Clock clock;
  private final int batchSize;
  private final Duration dispatchRetryDelay;

  public DocumentQueuePoller(
      DocumentRepository documentRepository,
      DocumentStatusTransitions transitions,
      DocumentExtractionWorker worker,
      Clock clock,
      @Value("${ledgerly.document.queue.batch-size:100}") int batchSize,
      @Value("${ledgerly.document.queue.dispatch-retry-seconds:5}") long dispatchRetrySeconds) {
    if (batchSize < 1 || dispatchRetrySeconds < 1) {
      throw new IllegalArgumentException("Document queue batch size must be positive");
    }
    this.documentRepository = documentRepository;
    this.transitions = transitions;
    this.worker = worker;
    this.clock = clock;
    this.batchSize = batchSize;
    this.dispatchRetryDelay = Duration.ofSeconds(dispatchRetrySeconds);
  }

  @Scheduled(
      fixedDelayString = "${ledgerly.document.queue.interval-seconds:5}",
      timeUnit = java.util.concurrent.TimeUnit.SECONDS)
  public void processDueDocuments() {
    Instant now = Instant.now(clock);
    Pageable oneBatch = PageRequest.of(0, batchSize, Sort.by("nextAttemptAt").ascending());
    List<Document> candidates =
        documentRepository.findByStatusAndNextAttemptAtLessThanEqual(DocumentStatus.PENDING, now, oneBatch);

    for (Document candidate : candidates) {
      if (transitions.claimDueDocument(candidate.getId(), candidate.getOrganizationId(), now)) {
        try {
          worker.extractAsync(candidate.getId(), candidate.getOrganizationId(), null);
        } catch (TaskRejectedException e) {
          transitions.releaseClaimAfterDispatchRejection(
              candidate.getId(), candidate.getOrganizationId(), now, now.plus(dispatchRetryDelay));
        }
      }
    }
  }
}
