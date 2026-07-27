package com.ledgerly.api.document;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Entry point for driving a document from {@code PENDING} to {@code PROCESSING} and handing off
 * the actual extraction.
 *
 * <p>The extraction work itself lives in {@link DocumentExtractionWorker}, a separate bean, not a
 * method here — a {@code @Async} method invoked via {@code this} from inside the same bean bypasses
 * the Spring proxy entirely and runs synchronously on the caller's thread, exactly the same trap
 * documented on {@link DocumentStatusTransitions} for {@code @Transactional}. Calling through an
 * injected reference is the only way the executor hand-off actually happens.
 */
@Service
public class DocumentProcessingService {

  private final DocumentStatusTransitions transitions;
  private final DocumentExtractionWorker worker;

  public DocumentProcessingService(
      DocumentStatusTransitions transitions, DocumentExtractionWorker worker) {
    this.transitions = transitions;
    this.worker = worker;
  }

  /**
   * Marks the document {@code PROCESSING} synchronously (so the upload response can say so
   * immediately), then dispatches the actual extraction to run off-thread.
   *
   * <p>The correlation id is captured here, on the request thread, because MDC is thread-local and
   * cleared the instant this request finishes — by the time {@link DocumentExtractionWorker} runs on
   * an executor thread, {@link CorrelationIdHolder#current()} would see nothing.
   */
  public Document beginProcessing(UUID documentId, UUID organizationId) {
    transitions.markProcessing(documentId, organizationId);
    Document document = transitions.load(documentId, organizationId);
    worker.extractAsync(documentId, organizationId, CorrelationIdHolder.current());
    return document;
  }
}
