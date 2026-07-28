package com.ledgerly.api.dashboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC read queries backing {@code GET /api/v1/dashboard/summary} — aggregates over {@code
 * expense} and {@code document}, both of which already have JPA entities, but these are
 * cross-cutting reporting queries rather than single-entity CRUD, so they live here rather than
 * being bolted onto {@code ExpenseRepository}/{@code DocumentRepository} as derived methods
 * {@code @Query} can't express.
 */
@Repository
public class DashboardRepository {

  private final JdbcTemplate jdbcTemplate;

  public DashboardRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Total POSTED spend, grouped by {@code expense.currency} — see {@link CurrencyTotal}. */
  public List<CurrencyTotal> totalsByCurrency(UUID organizationId, LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT currency, COALESCE(SUM(amount_minor), 0) AS total "
            + "FROM expense "
            + "WHERE organization_id = ? AND status = 'POSTED' "
            + "AND created_at >= ? AND created_at < ? "
            + "GROUP BY currency "
            + "ORDER BY currency",
        (rs, rowNum) -> new CurrencyTotal(rs.getString("currency"), rs.getLong("total")),
        organizationId,
        java.sql.Timestamp.valueOf(from.atStartOfDay()),
        java.sql.Timestamp.valueOf(to.atStartOfDay()));
  }

  /** POSTED spend by category, for the current calendar month, highest amount first. */
  public List<CategoryBreakdownEntry> categoryBreakdown(UUID organizationId, LocalDate monthStart, LocalDate monthEnd) {
    return jdbcTemplate.query(
        "SELECT c.id AS category_id, c.name AS category_name, "
            + "COALESCE(SUM(e.amount_minor), 0) AS total "
            + "FROM category c "
            + "JOIN expense e ON e.category_id = c.id AND e.status = 'POSTED' "
            + "AND e.created_at >= ? AND e.created_at < ? "
            + "WHERE c.organization_id = ? "
            + "GROUP BY c.id, c.name "
            + "HAVING COALESCE(SUM(e.amount_minor), 0) > 0 "
            + "ORDER BY total DESC",
        (rs, rowNum) ->
            new CategoryBreakdownEntry(
                (UUID) rs.getObject("category_id"), rs.getString("category_name"), rs.getLong("total")),
        java.sql.Timestamp.valueOf(monthStart.atStartOfDay()),
        java.sql.Timestamp.valueOf(monthEnd.atStartOfDay()),
        organizationId);
  }

  /**
   * POSTED spend per month for the given contiguous range of months, in the org's most common
   * currency for that window — callers with a genuinely mixed-currency history should treat this
   * series as approximate, same caveat as the rest of this milestone's currency handling. Every
   * month in {@code months} appears in the result, zero-filled if it has no POSTED spend; the
   * caller supplies the month list so this method has no month-count assumption baked in.
   */
  public List<MonthlySpend> monthlySeries(UUID organizationId, List<YearMonth> months) {
    if (months.isEmpty()) {
      return List.of();
    }
    YearMonth earliest = months.get(0);
    YearMonth latest = months.get(months.size() - 1);
    List<Object[]> rows =
        jdbcTemplate.query(
            "SELECT date_trunc('month', created_at) AS month, SUM(amount_minor) AS total "
                + "FROM expense "
                + "WHERE organization_id = ? AND status = 'POSTED' "
                + "AND created_at >= ? AND created_at < ? "
                + "GROUP BY date_trunc('month', created_at)",
            (rs, rowNum) ->
                new Object[] {
                  YearMonth.from(rs.getTimestamp("month").toInstant().atZone(ZoneOffset.UTC)),
                  rs.getLong("total")
                },
            organizationId,
            java.sql.Timestamp.valueOf(earliest.atDay(1).atStartOfDay()),
            java.sql.Timestamp.valueOf(latest.plusMonths(1).atDay(1).atStartOfDay()));

    Map<YearMonth, Long> byMonth = new LinkedHashMap<>();
    for (Object[] row : rows) {
      byMonth.put((YearMonth) row[0], (Long) row[1]);
    }
    List<MonthlySpend> series = new ArrayList<>(months.size());
    for (YearMonth month : months) {
      series.add(new MonthlySpend(month, byMonth.getOrDefault(month, 0L)));
    }
    return series;
  }

  public long countByStatus(UUID organizationId, String status) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense WHERE organization_id = ? AND status = ?",
            Long.class,
            organizationId,
            status);
    return count == null ? 0 : count;
  }

  /** Documents that reached a terminal status ({@code EXTRACTED}/{@code NEEDS_REVIEW}/{@code
   * FAILED}) since {@code since}. */
  public long documentsProcessedSince(UUID organizationId, LocalDate since) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document "
                + "WHERE organization_id = ? "
                + "AND status IN ('EXTRACTED', 'NEEDS_REVIEW', 'FAILED') "
                + "AND updated_at >= ?",
            Long.class,
            organizationId,
            java.sql.Timestamp.valueOf(since.atStartOfDay()));
    return count == null ? 0 : count;
  }
}
