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

  @Column(name = "category_id", nullable = false, updatable = false)
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
