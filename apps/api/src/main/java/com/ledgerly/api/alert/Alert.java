package com.ledgerly.api.alert;

import com.ledgerly.api.budget.Budget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.math.BigDecimal;
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

  @Column(name = "threshold_percent", updatable = false)
  private Integer thresholdPercent;

  @Column(name = "spent_minor", updatable = false)
  private Long spentMinor;

  @Column(name = "limit_minor", updatable = false)
  private Long limitMinor;

  @Column(name = "history_count", updatable = false)
  private Integer historyCount;

  @Column(name = "z_score", updatable = false)
  private BigDecimal zScore;

  @Column(name = "budget_burn_rate", updatable = false)
  private BigDecimal budgetBurnRate;

  @Column(updatable = false)
  private String explanation;

  @Column(updatable = false)
  private String model;

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

  public UUID getExpenseId() { return expenseId; }
  public UUID getCategoryId() { return categoryId; }
  public String getPeriod() { return period; }
  public String getCurrency() { return currency; }
  public String getAlertType() { return alertType; }
  public Integer getThresholdPercent() { return thresholdPercent; }
  public Long getSpentMinor() { return spentMinor; }
  public Long getLimitMinor() { return limitMinor; }
  public Integer getHistoryCount() { return historyCount; }
  public BigDecimal getZScore() { return zScore; }
  public BigDecimal getBudgetBurnRate() { return budgetBurnRate; }
  public String getExplanation() { return explanation; }
  public String getModel() { return model; }
  public Instant getCreatedAt() { return createdAt; }

  public static Alert anomalyHigh(
      UUID organizationId,
      UUID expenseId,
      UUID categoryId,
      String period,
      String currency,
      Long spentMinor,
      Long limitMinor,
      int historyCount,
      double zScore,
      Double budgetBurnRate,
      String explanation,
      String model) {
    Alert alert = new Alert();
    alert.organizationId = organizationId;
    alert.expenseId = expenseId;
    alert.categoryId = categoryId;
    alert.period = period;
    alert.currency = currency;
    alert.alertType = "ANOMALY_HIGH";
    alert.spentMinor = spentMinor;
    alert.limitMinor = limitMinor;
    alert.historyCount = historyCount;
    alert.zScore = BigDecimal.valueOf(zScore);
    alert.budgetBurnRate = budgetBurnRate == null ? null : BigDecimal.valueOf(budgetBurnRate);
    alert.explanation = explanation;
    alert.model = model;
    alert.createdAt = Instant.now();
    return alert;
  }
}
