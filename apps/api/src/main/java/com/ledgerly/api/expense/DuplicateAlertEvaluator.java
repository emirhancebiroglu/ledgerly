package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.alert.Alert;
import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.correlation.CorrelationIds;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Emits a {@code DUPLICATE_SUSPECTED} alert the first time {@link DuplicateMatcher} finds an
 * earlier expense a newly recorded one appears to duplicate. Runs from the same transaction
 * boundary as {@link com.ledgerly.api.budget.BudgetThresholdEvaluator}, but deliberately never
 * lets a failure inside detection propagate — a duplicate check is advisory, and a bug in it must
 * never turn into a technical failure of a real posting. */
@Service
public class DuplicateAlertEvaluator {

  private static final Logger log = LoggerFactory.getLogger(DuplicateAlertEvaluator.class);
  private static final String ALERT_TYPE = "DUPLICATE_SUSPECTED";

  private final DuplicateMatcher duplicateMatcher;
  private final AlertRepository alertRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public DuplicateAlertEvaluator(
      DuplicateMatcher duplicateMatcher,
      AlertRepository alertRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.duplicateMatcher = duplicateMatcher;
    this.alertRepository = alertRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /** Caller has already persisted {@code expense} in this same transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void evaluate(Expense expense, UUID actor) {
    try {
      evaluateOrThrow(expense, actor);
    } catch (RuntimeException exception) {
      log.warn(
          "Duplicate detection failed for expenseId={}; posting proceeds unaffected",
          expense.getId(),
          exception);
    }
  }

  private void evaluateOrThrow(Expense expense, UUID actor) {
    if (alertRepository.existsByExpenseIdAndAlertType(expense.getId(), ALERT_TYPE)) {
      return;
    }
    Optional<DuplicateMatch> match = duplicateMatcher.findDuplicate(expense);
    if (match.isEmpty()) {
      return;
    }
    String period = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC)).toString();
    Alert alert =
        alertRepository.save(
            Alert.duplicateSuspected(
                expense.getOrganizationId(),
                expense.getId(),
                expense.getCategoryId(),
                period,
                expense.getCurrency(),
                match.get().matchedExpenseId(),
                match.get().tier().name()));
    alertRepository.flush();
    auditService.record(
        expense.getOrganizationId(),
        actor,
        "CREATE",
        "alert",
        alert.getId(),
        null,
        auditPayload(expense, match.get()),
        CorrelationIds.current());
  }

  private String auditPayload(Expense expense, DuplicateMatch match) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "expenseId", expense.getId(),
              "matchedExpenseId", match.matchedExpenseId(),
              "tier", match.tier().name()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize alert for audit trail", exception);
    }
  }
}
