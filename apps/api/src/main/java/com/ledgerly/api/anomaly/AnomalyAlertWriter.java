package com.ledgerly.api.anomaly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.expense.Expense;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnomalyAlertWriter {
  private final JdbcTemplate jdbcTemplate;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public AnomalyAlertWriter(JdbcTemplate jdbcTemplate, AuditService auditService, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(Expense expense, AnomalyResponse response, java.time.Instant postedAt, java.util.UUID actor) {
    java.util.UUID alertId = java.util.UUID.randomUUID();
    var ids = jdbcTemplate.query(
        "INSERT INTO alert (id, organization_id, expense_id, category_id, period, currency, alert_type, "
            + "history_count, z_score, budget_burn_rate, explanation, model) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'ANOMALY_HIGH', ?, ?, ?, ?, ?) "
            + "ON CONFLICT (expense_id) WHERE alert_type = 'ANOMALY_HIGH' DO NOTHING RETURNING id",
        (rs, row) -> (java.util.UUID) rs.getObject("id"), alertId, expense.getOrganizationId(), expense.getId(),
        expense.getCategoryId(), YearMonth.from(postedAt.atZone(ZoneOffset.UTC)).toString(), expense.getCurrency(),
        response.historyCount(), java.math.BigDecimal.valueOf(response.zScore()),
        response.budgetBurnRate() == null ? null : java.math.BigDecimal.valueOf(response.budgetBurnRate()),
        response.explanation(), response.model());
    if (ids.isEmpty()) return;
    Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("expenseId", expense.getId()); payload.put("risk", response.risk()); payload.put("zScore", response.zScore());
    payload.put("historyCount", response.historyCount()); payload.put("budgetBurnRate", response.budgetBurnRate()); payload.put("model", response.model());
    auditService.record(expense.getOrganizationId(), actor, "CREATE", "alert", ids.getFirst(), null,
        json(payload),
        CorrelationIds.current());
  }

  private String json(Map<String, Object> value) {
    try { return objectMapper.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException("Could not serialize anomaly alert audit", exception); }
  }

}
