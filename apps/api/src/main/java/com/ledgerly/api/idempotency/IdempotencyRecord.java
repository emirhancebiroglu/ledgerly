package com.ledgerly.api.idempotency;

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

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "key", nullable = false)
  private String key;

  @Column(name = "endpoint", nullable = false)
  private String endpoint;

  @Column(name = "request_hash", nullable = false)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private IdempotencyStatus status;

  @Column
  private String response;

  @Column(name = "response_status")
  private Integer responseStatus;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(
      UUID organizationId, String key, String endpoint, String requestHash, Instant expiresAt) {
    this.organizationId = organizationId;
    this.key = key;
    this.endpoint = endpoint;
    this.requestHash = requestHash;
    this.status = IdempotencyStatus.IN_PROGRESS;
    this.expiresAt = expiresAt;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public IdempotencyStatus getStatus() {
    return status;
  }

  public String getResponse() {
    return response;
  }

  public Integer getResponseStatus() {
    return responseStatus;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public boolean isExpired(Instant now) {
    return expiresAt.isBefore(now);
  }

  public void complete(int responseStatus, String response) {
    this.status = IdempotencyStatus.COMPLETED;
    this.responseStatus = responseStatus;
    this.response = response;
  }
}
