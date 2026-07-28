package com.ledgerly.api.alert;

import com.ledgerly.api.budget.Budget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable budget-threshold alert snapshot; alert mutation is deliberately not modelled. */
@Entity
@Table(name = "alert")
public class Alert {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "expense_id", nullable = false, updatable = false)
  private UUID expenseId;

  @Column(name = "budget_id", updatable = false)
  private UUID budgetId;

  @Column(name = "category_id", nullable = false, updatable = false)
  private UUID categoryId;

  @Column(nullable = false, updatable = false, length = 7)
  private String period;

  @Column(nullable = false, updatable = false, columnDefinition = "bpchar(3)")
  private String currency;

  @Column(name = "alert_type", nullable = false, updatable = false)
  private String alertType;

  @Column(name = "threshold_percent", nullable = false, updatable = false)
  private int thresholdPercent;

  @Column(name = "spent_minor", nullable = false, updatable = false)
  private long spentMinor;

  @Column(name = "limit_minor", nullable = false, updatable = false)
  private long limitMinor;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Alert() {}

  private Alert(
      UUID organizationId,
      UUID expenseId,
      UUID budgetId,
      UUID categoryId,
      String period,
      String currency,
      int thresholdPercent,
      long spentMinor,
      long limitMinor) {
    this.organizationId = organizationId;
    this.expenseId = expenseId;
    this.budgetId = budgetId;
    this.categoryId = categoryId;
    this.period = period;
    this.currency = currency;
    this.alertType = "BUDGET_THRESHOLD";
    this.thresholdPercent = thresholdPercent;
    this.spentMinor = spentMinor;
    this.limitMinor = limitMinor;
    this.createdAt = Instant.now();
  }

  public static Alert budgetThreshold(
      UUID organizationId,
      UUID expenseId,
      Budget budget,
      int thresholdPercent,
      long spentMinor) {
    return new Alert(
        organizationId,
        expenseId,
        budget.getId(),
        budget.getCategoryId(),
        budget.getPeriod(),
        budget.getCurrency(),
        thresholdPercent,
        spentMinor,
        budget.getLimitMinor());
  }

  public UUID getId() {
    return id;
  }
}
