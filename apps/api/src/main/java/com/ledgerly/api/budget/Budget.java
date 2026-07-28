package com.ledgerly.api.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An exact-money monthly limit for one organization category and currency. */
@Entity
@Table(name = "budget")
public class Budget {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(nullable = false, length = 7)
  private String period;

  @Column(name = "limit_minor", nullable = false)
  private long limitMinor;

  @Column(nullable = false, columnDefinition = "bpchar(3)")
  private String currency;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Budget() {}

  public Budget(UUID organizationId, UUID categoryId, String period, long limitMinor, String currency) {
    this.organizationId = organizationId;
    this.categoryId = categoryId;
    this.period = period;
    this.limitMinor = limitMinor;
    this.currency = currency;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void update(UUID categoryId, String period, long limitMinor, String currency) {
    this.categoryId = categoryId;
    this.period = period;
    this.limitMinor = limitMinor;
    this.currency = currency;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public String getPeriod() {
    return period;
  }

  public long getLimitMinor() {
    return limitMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
