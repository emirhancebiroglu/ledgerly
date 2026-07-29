package com.ledgerly.api.document;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Appends durable document activity and exposes an org-scoped ordered replay query. */
@Service
public class DocumentActivityService {

  private final DocumentActivityRepository repository;
  private final DocumentRepository documentRepository;
  private final ApplicationEventPublisher eventPublisher;

  public DocumentActivityService(
      DocumentActivityRepository repository,
      DocumentRepository documentRepository,
      ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.documentRepository = documentRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public DocumentActivityResponse record(
      UUID documentId, UUID organizationId, DocumentActivityStage stage, String detail) {
    documentRepository
        .lockByIdAndOrganizationId(documentId, organizationId)
        .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
    DocumentActivity activity =
        repository.save(new DocumentActivity(documentId, organizationId, stage, detail));
    eventPublisher.publishEvent(
        new DocumentActivityRecordedEvent(activity.getId(), documentId, organizationId));
    return DocumentActivityResponse.from(activity);
  }

  public List<DocumentActivityResponse> replay(UUID documentId, UUID organizationId, long afterId) {
    return repository
        .findByDocumentIdAndOrganizationIdAndIdGreaterThanOrderByIdAsc(
            documentId, organizationId, afterId)
        .stream()
        .map(DocumentActivityResponse::from)
        .toList();
  }
}
