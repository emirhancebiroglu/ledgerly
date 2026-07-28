package com.ledgerly.api.anomaly;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded, tenant-scoped source of truth for the anomaly service's historical input. */
@Repository
public class AnomalyHistoryRepository {

  private final JdbcTemplate jdbcTemplate;

  public AnomalyHistoryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<AnomalyHistoryEntry> findPostedHistory(
      UUID organizationId,
      UUID categoryId,
      String currency,
      UUID candidateExpenseId,
      Instant candidatePostedAt,
      int limit) {
    return jdbcTemplate.query(
        "SELECT e.amount_minor, lt.posted_at FROM expense e "
            + "JOIN ledger_transaction lt ON lt.id = e.ledger_transaction_id "
            + "WHERE e.organization_id = ? AND e.category_id = ? AND e.currency = ? "
            + "AND e.status = 'POSTED' AND e.id <> ? "
            + "AND lt.posted_at >= ? AND lt.posted_at < ? "
            + "ORDER BY lt.posted_at DESC LIMIT ?",
        (rs, rowNum) ->
            new AnomalyHistoryEntry(
                rs.getLong("amount_minor"), rs.getTimestamp("posted_at").toInstant()),
        organizationId,
        categoryId,
        currency,
        candidateExpenseId,
        Timestamp.from(candidatePostedAt.minus(java.time.Duration.ofDays(90))),
        Timestamp.from(candidatePostedAt),
        limit);
  }
}
