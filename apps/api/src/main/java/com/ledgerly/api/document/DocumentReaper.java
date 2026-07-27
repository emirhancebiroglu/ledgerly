package com.ledgerly.api.document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims a document stuck in {@code PROCESSING} — a crash between {@link
 * DocumentStatusTransitions#markProcessing} and the eventual outcome write (M4's known follow-up,
 * closed here rather than carried to M9). {@code FAILED} is the only legal target from {@code
 * PROCESSING} besides the two success outcomes, so a reaped document is indistinguishable in the
 * status lifecycle from any other extraction failure — a human reviews it the same way.
 *
 * <p>Safe with two instances running: {@link DocumentRepository#reclaimStuckDocument} is a single
 * conditional {@code UPDATE ... WHERE status = 'PROCESSING'}, so whichever instance's statement
 * commits first flips the row and the other's matches zero rows. Selecting candidates and
 * reclaiming them are two separate steps deliberately — a batch {@code SELECT} across all
 * organizations followed by a per-row atomic {@code UPDATE} means a document that resolves
 * normally between the two never gets clobbered by a reaper that read it a moment too early.
 */
@Component
public class DocumentReaper {

  private static final Logger log = LoggerFactory.getLogger(DocumentReaper.class);
  private static final String TIMEOUT_REASON = "Extraction timed out: reclaimed by the reaper";

  private final DocumentRepository documentRepository;
  private final DocumentStatusTransitions transitions;
  private final Clock clock;
  private final Duration stuckAfter;

  public DocumentReaper(
      DocumentRepository documentRepository,
      DocumentStatusTransitions transitions,
      Clock clock,
      @Value("${ledgerly.document.reaper.stuck-after-seconds:300}") long stuckAfterSeconds) {
    this.documentRepository = documentRepository;
    this.transitions = transitions;
    this.clock = clock;
    this.stuckAfter = Duration.ofSeconds(stuckAfterSeconds);
  }

  @Scheduled(
      fixedDelayString = "${ledgerly.document.reaper.interval-seconds:60}",
      timeUnit = java.util.concurrent.TimeUnit.SECONDS)
  public void reclaimStuckDocuments() {
    Instant cutoff = Instant.now(clock).minus(stuckAfter);
    List<Document> candidates =
        documentRepository.findByStatusAndUpdatedAtBefore(DocumentStatus.PROCESSING, cutoff);

    for (Document candidate : candidates) {
      boolean reclaimed =
          transitions.reclaimStuckDocument(candidate.getId(), cutoff, Instant.now(clock), TIMEOUT_REASON);
      if (reclaimed) {
        log.warn("Reaped stuck document {} (PROCESSING since before {})", candidate.getId(), cutoff);
      }
      // A false result means another instance already reclaimed it, or it resolved normally
      // between the SELECT and this UPDATE — either way, correctly a no-op here.
    }
  }
}
