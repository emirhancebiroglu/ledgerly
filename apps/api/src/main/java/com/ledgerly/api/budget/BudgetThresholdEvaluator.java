package com.ledgerly.api.budget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.alert.Alert;
import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.expense.Expense;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Emits each deterministic 80%/100% budget threshold alert at most once. */
@Service
public class BudgetThresholdEvaluator {

  private static final List<Integer> THRESHOLDS = List.of(80, 100);

  private final BudgetRepository budgetRepository;
  private final BudgetSpendRepository budgetSpendRepository;
  private final AlertRepository alertRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public BudgetThresholdEvaluator(
      BudgetRepository budgetRepository,
      BudgetSpendRepository budgetSpendRepository,
      AlertRepository alertRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.budgetRepository = budgetRepository;
    this.budgetSpendRepository = budgetSpendRepository;
    this.alertRepository = alertRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /** Caller has already persisted the expense and ledger transaction in this same transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void evaluate(Expense expense, Instant postedAt, UUID actor) {
    String period = YearMonth.from(postedAt.atZone(ZoneOffset.UTC)).toString();
    Optional<Budget> matchingBudget =
        budgetRepository.findForEvaluation(
            expense.getOrganizationId(), expense.getCategoryId(), period, expense.getCurrency());
    if (matchingBudget.isEmpty()) {
      return;
    }

    Budget budget = matchingBudget.get();
    long spentMinor = budgetSpendRepository.spentMinorByBudget(List.of(budget)).get(budget.getId());
    for (int threshold : THRESHOLDS) {
      if (reachedThreshold(spentMinor, budget.getLimitMinor(), threshold)
          && !alertRepository.existsByBudgetIdAndThresholdPercent(budget.getId(), threshold)) {
        recordAlert(expense, budget, threshold, spentMinor, actor);
      }
    }
  }

  private boolean reachedThreshold(long spentMinor, long limitMinor, int thresholdPercent) {
    return BigDecimal.valueOf(spentMinor)
            .multiply(BigDecimal.valueOf(100))
            .compareTo(
                BigDecimal.valueOf(limitMinor).multiply(BigDecimal.valueOf(thresholdPercent)))
        >= 0;
  }

  private void recordAlert(
      Expense expense, Budget budget, int threshold, long spentMinor, UUID actor) {
    Alert alert =
        alertRepository.save(
            Alert.budgetThreshold(
                expense.getOrganizationId(), expense.getId(), budget, threshold, spentMinor));
    alertRepository.flush();
    auditService.record(
        expense.getOrganizationId(),
        actor,
        "CREATE",
        "alert",
        alert.getId(),
        null,
        auditPayload(expense, budget, threshold, spentMinor),
        CorrelationIds.current());
  }

  private String auditPayload(Expense expense, Budget budget, int threshold, long spentMinor) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "expenseId", expense.getId(),
              "budgetId", budget.getId(),
              "thresholdPercent", threshold,
              "spentMinor", spentMinor,
              "limitMinor", budget.getLimitMinor(),
              "currency", budget.getCurrency()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize alert for audit trail", exception);
    }
  }
}
