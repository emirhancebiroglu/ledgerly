package com.ledgerly.api.budget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Batch ledger-derived spend reads for budgets. The database, not a cached counter, is truth. */
@Repository
public class BudgetSpendRepository {

  private final JdbcTemplate jdbcTemplate;

  public BudgetSpendRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** One bounded query for every budget in a page, avoiding a per-card N+1 aggregate query. */
  public Map<UUID, Long> spentMinorByBudget(List<Budget> budgets) {
    if (budgets.isEmpty()) {
      return Map.of();
    }

    String placeholders = budgets.stream().map(ignored -> "?").collect(Collectors.joining(", "));
    String sql =
        "SELECT b.id, COALESCE(("
            + "SELECT SUM(e.amount_minor) "
            + "FROM expense e "
            + "JOIN ledger_transaction lt ON lt.id = e.ledger_transaction_id "
            + "WHERE e.organization_id = b.organization_id "
            + "AND e.category_id = b.category_id "
            + "AND e.currency = b.currency "
            + "AND e.status = 'POSTED' "
            + "AND lt.posted_at >= (b.period || '-01')::date AT TIME ZONE 'UTC' "
            + "AND lt.posted_at < ((b.period || '-01')::date + INTERVAL '1 month') AT TIME ZONE 'UTC'"
            + "), 0) AS spent "
            + "FROM budget b WHERE b.id IN ("
            + placeholders
            + ")";

    Object[] ids = budgets.stream().map(Budget::getId).toArray();
    Map<UUID, Long> spentByBudget = new HashMap<>();
    jdbcTemplate.query(
        sql,
        (RowCallbackHandler)
            rs -> spentByBudget.put((UUID) rs.getObject("id"), rs.getLong("spent")),
        ids);
    return spentByBudget;
  }
}
