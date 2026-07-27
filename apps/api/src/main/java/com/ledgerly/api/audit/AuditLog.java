package com.ledgerly.api.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private UUID actor;

  @Column(nullable = false)
  private String action;

  @Column(name = "entity_type", nullable = false)
  private String entityType;

  @Column(name = "entity_id", nullable = false)
  private UUID entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String before;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String after;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(
      UUID organizationId,
      UUID actor,
      String action,
      String entityType,
      UUID entityId,
      String before,
      String after,
      UUID correlationId) {
    this.organizationId = organizationId;
    this.actor = actor;
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
    this.before = before;
    this.after = after;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getActor() {
    return actor;
  }

  public String getAction() {
    return action;
  }

  public String getEntityType() {
    return entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public String getBefore() {
    return before;
  }

  public String getAfter() {
    return after;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }
}
