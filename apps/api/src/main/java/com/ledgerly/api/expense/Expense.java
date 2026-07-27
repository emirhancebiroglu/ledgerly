package com.ledgerly.api.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The business-level record linking a categorized document to the ledger transaction it produced
 * — architecture.md §3's {@code expense} node. Distinct from {@link ExpenseStub}, which exercises
 * the M3 idempotency filter and is unrelated to this pipeline.
 *
 * <p>{@code ledgerTransactionId} is null for a {@code NEEDS_REVIEW} expense: the categorization
 * result is recorded, but nothing is posted to the ledger until a human approves it (M6 T7).
 */
@Entity
@Table(name = "expense")
public class Expense {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(updatable = false)
  private String vendor;

  /**
   * Updatable, unlike the rest of this entity's history-of-record fields: a review-queue
   * resolution (approve keeps the AI's choice, correct replaces it) is the one legitimate
   * post-creation write, made by {@link ExpenseReviewTransactions}.
   */
  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "ledger_transaction_id")
  private UUID ledgerTransactionId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @Column(nullable = false, updatable = false)
  private String currency;

  @Column(name = "categorization_confidence", nullable = false, updatable = false)
  private double categorizationConfidence;

  @Column(updatable = false)
  private String citation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ExpenseStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Expense() {}

  private Expense(
      UUID organizationId,
      UUID documentId,
      String vendor,
      UUID categoryId,
      UUID ledgerTransactionId,
      long amountMinor,
      String currency,
      double categorizationConfidence,
      String citation,
      ExpenseStatus status) {
    this.organizationId = organizationId;
    this.documentId = documentId;
    this.vendor = vendor;
    this.categoryId = categoryId;
    this.ledgerTransactionId = ledgerTransactionId;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.categorizationConfidence = categorizationConfidence;
    this.citation = citation;
    this.status = status;
    this.createdAt = Instant.now();
  }

  /** A posted expense — categorization cleared the confidence threshold, ledger entry exists. */
  public static Expense posted(
      UUID organizationId,
      UUID documentId,
      String vendor,
      UUID categoryId,
      UUID ledgerTransactionId,
      long amountMinor,
      String currency,
      double categorizationConfidence,
      String citation) {
    return new Expense(
        organizationId,
        documentId,
        vendor,
        categoryId,
        ledgerTransactionId,
        amountMinor,
        currency,
        categorizationConfidence,
        citation,
        ExpenseStatus.POSTED);
  }

  /** A review-queue expense — confidence below threshold, no ledger entry exists. */
  public static Expense needsReview(
      UUID organizationId,
      UUID documentId,
      String vendor,
      UUID categoryId,
      long amountMinor,
      String currency,
      double categorizationConfidence,
      String citation) {
    return new Expense(
        organizationId,
        documentId,
        vendor,
        categoryId,
        null,
        amountMinor,
        currency,
        categorizationConfidence,
        citation,
        ExpenseStatus.NEEDS_REVIEW);
  }

  /**
   * Resolves a {@code NEEDS_REVIEW} expense to {@code POSTED} against the given category and the
   * ledger transaction just built for it — a human approval (category unchanged) or correction
   * (category replaced) from {@link ExpenseReviewTransactions}. Confidence and citation are left
   * as the AI produced them; they describe what the model proposed, not what the human decided.
   *
   * @throws ExpenseAlreadyResolvedException if this expense is not currently {@code NEEDS_REVIEW}
   *     — the domain enforces "resolved exactly once," not just the service calling it once.
   */
  public void resolve(UUID categoryId, UUID ledgerTransactionId) {
    if (status != ExpenseStatus.NEEDS_REVIEW) {
      throw new ExpenseAlreadyResolvedException(id);
    }
    this.categoryId = categoryId;
    this.ledgerTransactionId = ledgerTransactionId;
    this.status = ExpenseStatus.POSTED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public String getVendor() {
    return vendor;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public UUID getLedgerTransactionId() {
    return ledgerTransactionId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public double getCategorizationConfidence() {
    return categorizationConfidence;
  }

  public String getCitation() {
    return citation;
  }

  public ExpenseStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
