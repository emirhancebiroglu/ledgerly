package com.ledgerly.api.policy;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to policy documents and their indexed chunks, org-scoped. Mutations (upload) stay
 * in {@link PolicyUploadService}; this service only assembles response shapes for it and for the
 * list/chunk endpoints.
 */
@Service
public class PolicyQueryService {

  private static final int MAX_DOCUMENT_PAGE_SIZE = 100;
  private static final int MAX_CHUNK_PAGE_SIZE = 200;

  private final PolicyDocumentRepository policyDocumentRepository;
  private final PolicyChunkRepository policyChunkRepository;

  public PolicyQueryService(
      PolicyDocumentRepository policyDocumentRepository,
      PolicyChunkRepository policyChunkRepository) {
    this.policyDocumentRepository = policyDocumentRepository;
    this.policyChunkRepository = policyChunkRepository;
  }

  /** Wraps one already-loaded, already-org-checked document with its chunk count. */
  @Transactional(readOnly = true)
  public PolicyDocumentResponse toResponse(PolicyDocument document, AuthenticatedPrincipal principal) {
    long chunkCount = policyChunkRepository.countByPolicyDocumentId(document.getId());
    return PolicyDocumentResponse.from(document, chunkCount);
  }

  @Transactional(readOnly = true)
  public List<PolicyDocumentResponse> list(int page, int size, AuthenticatedPrincipal principal) {
    Pageable pageable =
        PageRequest.of(
            validatedPage(page), validatedSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
    UUID organizationId = principal.organizationId();

    List<PolicyDocument> documents =
        policyDocumentRepository.findByOrganizationId(organizationId, pageable);
    List<UUID> documentIds = documents.stream().map(PolicyDocument::getId).toList();
    Map<UUID, Long> chunkCounts =
        policyChunkRepository.countByPolicyDocumentIds(organizationId, documentIds);

    return documents.stream()
        .map(document -> PolicyDocumentResponse.from(document, chunkCounts.getOrDefault(document.getId(), 0L)))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PolicyChunkResponse> listChunks(
      UUID policyDocumentId, int page, int size, AuthenticatedPrincipal principal) {
    UUID organizationId = principal.organizationId();
    policyDocumentRepository
        .findByIdAndOrganizationId(policyDocumentId, organizationId)
        .orElseThrow(
            () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));

    int validatedPage = validatedPage(page);
    int validatedSize = validatedChunkSize(size);
    int offset = validatedPage * validatedSize;

    return policyChunkRepository
        .findByPolicyDocumentIdOrderByChunkIndex(
            organizationId, policyDocumentId, offset, validatedSize)
        .stream()
        .map(PolicyChunkResponse::from)
        .toList();
  }

  private int validatedPage(int page) {
    if (page < 0) {
      throw new InvalidPolicyListQueryException("page must not be negative: " + page);
    }
    return page;
  }

  private int validatedSize(int size) {
    if (size <= 0) {
      throw new InvalidPolicyListQueryException("size must be positive: " + size);
    }
    return Math.min(size, MAX_DOCUMENT_PAGE_SIZE);
  }

  private int validatedChunkSize(int size) {
    if (size <= 0) {
      throw new InvalidPolicyListQueryException("size must be positive: " + size);
    }
    return Math.min(size, MAX_CHUNK_PAGE_SIZE);
  }
}
