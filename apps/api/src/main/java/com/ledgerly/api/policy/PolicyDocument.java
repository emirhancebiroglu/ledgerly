package com.ledgerly.api.policy;

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
 * An uploaded expense-policy document, chunked and embedded for retrieval.
 *
 * <p>Status changes go through {@link #transitionTo}, never a plain setter — mirrors {@link
 * com.ledgerly.api.document.Document}.
 */
@Entity
@Table(name = "policy_document")
public class PolicyDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "uploaded_by", nullable = false, updatable = false)
  private UUID uploadedBy;

  @Column(nullable = false, updatable = false)
  private String filename;

  @Column(name = "storage_key", nullable = false, updatable = false)
  private String storageKey;

  @Column(name = "content_hash", nullable = false, updatable = false)
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PolicyDocumentStatus status;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PolicyDocument() {}

  public PolicyDocument(
      UUID organizationId,
      UUID uploadedBy,
      String filename,
      String storageKey,
      String contentHash) {
    this.organizationId = organizationId;
    this.uploadedBy = uploadedBy;
    this.filename = filename;
    this.storageKey = storageKey;
    this.contentHash = contentHash;
    this.status = PolicyDocumentStatus.PENDING;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void transitionTo(PolicyDocumentStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalPolicyDocumentTransitionException(status, target);
    }
    this.status = target;
    this.updatedAt = Instant.now();
  }

  public void markEmbedded() {
    transitionTo(PolicyDocumentStatus.EMBEDDED);
    this.failureReason = null;
  }

  public void markFailed(String reason) {
    transitionTo(PolicyDocumentStatus.FAILED);
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

  public String getStorageKey() {
    return storageKey;
  }

  public String getContentHash() {
    return contentHash;
  }

  public PolicyDocumentStatus getStatus() {
    return status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
