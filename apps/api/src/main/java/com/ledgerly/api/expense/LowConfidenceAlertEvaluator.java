package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.alert.Alert;
import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.correlation.CorrelationIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Emits a {@code LOW_CONFIDENCE} alert the first time an expense is routed to review for a
 * below-threshold categorization confidence. At most one per expense — {@code
 * uq_alert_low_confidence_expense} backstops the {@code existsBy} check against a concurrent
 * duplicate delivery of the same review event. */
@Service
public class LowConfidenceAlertEvaluator {

  private static final String ALERT_TYPE = "LOW_CONFIDENCE";

  private final AlertRepository alertRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public LowConfidenceAlertEvaluator(
      AlertRepository alertRepository, AuditService auditService, ObjectMapper objectMapper) {
    this.alertRepository = alertRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /** Caller has already persisted the review-routed expense in this same transaction. A no-op for
   * anything other than a {@code NEEDS_REVIEW} expense — this guard is what makes the evaluator
   * safe to call defensively rather than trusting every caller to only ever pass a review-routed
   * expense. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void evaluate(Expense expense, UUID actor) {
    if (expense.getStatus() != ExpenseStatus.NEEDS_REVIEW) {
      return;
    }
    if (alertRepository.existsByExpenseIdAndAlertType(expense.getId(), ALERT_TYPE)) {
      return;
    }
    String period = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC)).toString();
    Alert alert =
        alertRepository.save(
            Alert.lowConfidence(
                expense.getOrganizationId(),
                expense.getId(),
                expense.getCategoryId(),
                period,
                expense.getCurrency(),
                BigDecimal.valueOf(expense.getCategorizationConfidence())));
    alertRepository.flush();
    auditService.record(
        expense.getOrganizationId(),
        actor,
        "CREATE",
        "alert",
        alert.getId(),
        null,
        auditPayload(expense),
        CorrelationIds.current());
  }

  private String auditPayload(Expense expense) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "expenseId", expense.getId(),
              "categoryId", expense.getCategoryId(),
              "categorizationConfidence", expense.getCategorizationConfidence()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize alert for audit trail", exception);
    }
  }
}
