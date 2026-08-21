package com.ledgerly.api.expense;

import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.document.DocumentActivityService;
import com.ledgerly.api.document.DocumentActivityStage;
import com.ledgerly.api.policy.PolicyChunk;
import com.ledgerly.api.policy.PolicyChunkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * M6 T6/T7: categorize an extracted proposal, retrieve relevant policy chunks, and either post a
 * balanced ledger transaction or route the expense to review — never both, never neither.
 *
 * <p>Retrieval (embed the query text, then a pgvector nearest-neighbor search) and the
 * categorization call both happen here, outside any database transaction, for the same reason
 * {@link com.ledgerly.api.document.DocumentExtractionWorker} calls `ai` outside one: a slow
 * network call must never hold a connection open. The actual persistence — expense row, ledger
 * transaction and entries, audit row, all as one atomic write — is delegated to {@link
 * ExpensePostingTransactions}, a separate bean called via injection rather than {@code this} (see
 * that class's Javadoc for why).
 */
@Service
public class ExpensePostingService {

  private static final Logger log = LoggerFactory.getLogger(ExpensePostingService.class);

  private static final int POLICY_CHUNK_TOP_K = 3;

  private final CategoryRepository categoryRepository;
  private final PolicyChunkRepository policyChunkRepository;
  private final QueryEmbeddingClient queryEmbeddingClient;
  private final CategorizationClient categorizationClient;
  private final AiResponseMapper aiResponseMapper;
  private final ExpensePostingTransactions transactions;
  private final DocumentActivityService documentActivityService;
  private final double confidenceThreshold;

  public ExpensePostingService(
      CategoryRepository categoryRepository,
      PolicyChunkRepository policyChunkRepository,
      QueryEmbeddingClient queryEmbeddingClient,
      CategorizationClient categorizationClient,
      AiResponseMapper aiResponseMapper,
      ExpensePostingTransactions transactions,
      DocumentActivityService documentActivityService,
      @Value("${ledgerly.categorization.confidence-threshold:0.7}") double confidenceThreshold) {
    this.categoryRepository = categoryRepository;
    this.policyChunkRepository = policyChunkRepository;
    this.queryEmbeddingClient = queryEmbeddingClient;
    this.categorizationClient = categorizationClient;
    this.aiResponseMapper = aiResponseMapper;
    this.transactions = transactions;
    this.documentActivityService = documentActivityService;
    this.confidenceThreshold = confidenceThreshold;
  }

  /**
   * Categorizes an already-validated extraction proposal and posts or queues the resulting
   * expense. Called by {@link com.ledgerly.api.document.DocumentExtractionWorker} after a document
   * reaches {@code EXTRACTED} — never for a document that failed validation.
   *
   * @param actor the audit actor — categorization runs off the request thread with no
   *     authenticated principal, so the caller passes the document's uploader instead.
   * @throws CategorizationOutcomeException if categorization could not be completed at all (`ai`
   *     unavailable, malformed response, no category taxonomy exists yet for the organization, or
   *     `ai` chose a category outside the given list). The caller persists a correctable,
   *     unclassified review item; no ledger row is written.
   */
  public Expense categorizeAndPost(
      UUID organizationId, UUID documentId, UUID actor, ExtractionProposal proposal) {
    documentActivityService.record(
        documentId, organizationId, DocumentActivityStage.CATEGORIZING, "Categorizing expense");
    List<Category> categories =
        categoryRepository.findByOrganizationIdOrderByNameAsc(organizationId);
    if (categories.isEmpty()) {
      throw new CategorizationOutcomeException(
          "Organization has no category taxonomy; cannot categorize");
    }
    List<String> categoryNames = categories.stream().map(Category::getName).toList();

    List<PolicyChunk> relevantChunks = retrieveRelevantChunks(organizationId, proposal);
    CategorizeResponse response =
        callCategorize(documentId, proposal, categoryNames, relevantChunks);

    Category chosenCategory =
        categoryRepository
            .findByOrganizationIdAndName(organizationId, response.category())
            .orElseThrow(
                () ->
                    new CategorizationOutcomeException(
                        "ai chose a category outside the given taxonomy: " + response.category()));

    CategorizeResponse trustedResponse = withTrustedCitation(response, relevantChunks);

    try {
      if (trustedResponse.confidence() < confidenceThreshold) {
        return transactions.recordNeedsReview(
            organizationId, documentId, actor, chosenCategory, proposal, trustedResponse);
      }
      return transactions.recordPosted(
          organizationId, documentId, actor, chosenCategory, proposal, trustedResponse);
    } catch (DataIntegrityViolationException e) {
      // A category can be deleted after the AI response has been checked but before this
      // transaction writes its foreign key. That is an expected classification race, not a
      // technical posting failure; every other integrity failure remains observable.
      if (categoryRepository
          .findByIdAndOrganizationId(chosenCategory.getId(), organizationId)
          .isEmpty()) {
        throw new CategorizationOutcomeException("Categorization category was deleted before posting");
      }
      throw e;
    }
  }

  /** Records the no-category review fallback for an expected categorization outcome. */
  public Expense recordUnclassifiedNeedsReview(
      UUID organizationId, UUID documentId, UUID actor, ExtractionProposal proposal) {
    return transactions.recordUnclassifiedNeedsReview(organizationId, documentId, actor, proposal);
  }

  /**
   * `ai` is only told not to invent a citation via prompt text — that is not enforcement. A model
   * that paraphrases or fabricates a policy quote would otherwise have its invention stored as the
   * ledger-facing justification for a real posted expense. Scrubbing an untraceable citation to
   * {@code null} rather than failing the whole categorization keeps a genuinely correct category
   * choice from being discarded over an unrelated citation slip.
   */
  private CategorizeResponse withTrustedCitation(
      CategorizeResponse response, List<PolicyChunk> relevantChunks) {
    if (response.citation() == null) {
      return response;
    }
    boolean citationIsGenuine =
        relevantChunks.stream().anyMatch(chunk -> chunk.chunkText().equals(response.citation()));
    if (citationIsGenuine) {
      return response;
    }
    log.warn("Categorization returned a citation not present in the retrieved policy chunks");
    return new CategorizeResponse(
        response.documentId(), response.category(), response.confidence(), null, response.model());
  }

  private List<PolicyChunk> retrieveRelevantChunks(
      UUID organizationId, ExtractionProposal proposal) {
    if (policyChunkRepository.countByOrganizationId(organizationId) == 0) {
      return List.of();
    }

    String queryText = buildQueryText(proposal);
    EmbedQueryResponse embedded;
    try {
      embedded =
          aiResponseMapper.parseEmbedQueryResponse(queryEmbeddingClient.embedQuery(queryText));
    } catch (RuntimeException e) {
      // Retrieval is best-effort context, not a required step: categorization still runs with an
      // empty chunk list rather than failing the whole expense over a retrieval hiccup.
      log.warn(
          "Policy chunk retrieval failed, categorizing without policy context exceptionType={}",
          e.getClass().getSimpleName());
      return List.of();
    }

    float[] queryEmbedding = new float[embedded.embedding().size()];
    for (int i = 0; i < queryEmbedding.length; i++) {
      queryEmbedding[i] = embedded.embedding().get(i).floatValue();
    }
    return policyChunkRepository.findNearest(organizationId, queryEmbedding, POLICY_CHUNK_TOP_K);
  }

  private CategorizeResponse callCategorize(
      UUID documentId,
      ExtractionProposal proposal,
      List<String> categoryNames,
      List<PolicyChunk> relevantChunks) {
    String rawResponse;
    try {
      rawResponse =
          categorizationClient.categorize(
              documentId,
              proposal.vendor(),
              proposal.currency(),
              proposal.totalMinor(),
              proposal.documentDate() == null ? null : proposal.documentDate().toString(),
              categoryNames,
              relevantChunks.stream().map(PolicyChunk::chunkText).toList());
    } catch (RuntimeException e) {
      throw new CategorizationOutcomeException(
          "Categorization service call failed: " + e.getMessage());
    }

    try {
      return aiResponseMapper.parseCategorizeResponse(rawResponse);
    } catch (MalformedAiResponseException e) {
      throw new CategorizationOutcomeException(e.getMessage());
    }
  }

  private String buildQueryText(ExtractionProposal proposal) {
    return "Vendor: %s, currency: %s, total: %d minor units"
        .formatted(proposal.vendor(), proposal.currency(), proposal.totalMinor());
  }
}
