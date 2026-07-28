package com.ledgerly.api.document;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An uploaded document and the outcome of extracting it.
 *
 * <p>Status changes go through {@link #transitionTo}, never a plain setter — the entity refuses an
 * illegal transition itself, so no caller can move a {@code FAILED} document back into processing.
 */
@Entity
@Table(name = "document")
public class Document {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "uploaded_by", nullable = false, updatable = false)
  private UUID uploadedBy;

  @Column(nullable = false, updatable = false)
  private String filename;

  @Column(name = "content_type", nullable = false, updatable = false)
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(name = "storage_key", nullable = false, updatable = false)
  private String storageKey;

  @Column(name = "content_hash", nullable = false, updatable = false)
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DocumentStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String proposal;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "extraction_attempts", nullable = false)
  private int extractionAttempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Document() {}

  public Document(
      UUID organizationId,
      UUID uploadedBy,
      String filename,
      String contentType,
      long sizeBytes,
      String storageKey,
      String contentHash) {
    this.organizationId = organizationId;
    this.uploadedBy = uploadedBy;
    this.filename = filename;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
    this.contentHash = contentHash;
    this.status = DocumentStatus.PENDING;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
    this.nextAttemptAt = this.createdAt;
  }

  /**
   * Moves to {@code target}, or throws if the current status forbids it.
   *
   * @throws IllegalDocumentTransitionException if the transition is not legal
   */
  public void transitionTo(DocumentStatus target) {
    transitionTo(target, Instant.now());
  }

  private void transitionTo(DocumentStatus target, Instant now) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalDocumentTransitionException(status, target);
    }
    this.status = target;
    this.updatedAt = now;
  }

  /** Attaches a validated proposal and moves to {@code EXTRACTED} as one step. */
  public void markExtracted(String proposalJson) {
    transitionTo(DocumentStatus.EXTRACTED);
    this.proposal = proposalJson;
    this.failureReason = null;
  }

  /**
   * Records a proposal that failed validation and moves to {@code NEEDS_REVIEW}. The proposal is
   * kept deliberately: a human reviewing the queue needs to see what the agent actually claimed.
   */
  public void markNeedsReview(String proposalJson, String reason) {
    transitionTo(DocumentStatus.NEEDS_REVIEW);
    this.proposal = proposalJson;
    this.failureReason = reason;
  }

  /** Moves to {@code FAILED} — extraction could not produce a proposal at all. */
  public void markFailed(String reason) {
    transitionTo(DocumentStatus.FAILED);
    this.failureReason = reason;
  }

  /** Returns a transient extraction failure to the durable queue for a later attempt. */
  public void markPendingForRetry(Instant retryAt, String reason, Instant now) {
    transitionTo(DocumentStatus.PENDING, now);
    this.nextAttemptAt = retryAt;
    this.failureReason = reason;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public String getFilename() {
    return filename;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getContentHash() {
    return contentHash;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public String getProposal() {
    return proposal;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public int getExtractionAttempts() {
    return extractionAttempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }
}
