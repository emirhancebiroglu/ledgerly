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

/** A durable, append-only audit trail for one document's agent processing. */
@Entity
@Table(name = "document_activity")
public class DocumentActivity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private DocumentActivityStage stage;

  @Column(updatable = false)
  private String detail;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected DocumentActivity() {}

  public DocumentActivity(
      UUID documentId, UUID organizationId, DocumentActivityStage stage, String detail) {
    this.documentId = documentId;
    this.organizationId = organizationId;
    this.stage = stage;
    this.detail = detail;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public DocumentActivityStage getStage() {
    return stage;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
