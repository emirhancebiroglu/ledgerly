package com.ledgerly.api.dashboard;

import com.ledgerly.api.document.DocumentStatus;
import com.ledgerly.api.expense.ExpenseStatus;
import java.time.Instant;
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
        startOfDayUtc(from),
        startOfDayUtc(to));
  }

  /**
   * POSTED spend by category and currency, for the current calendar month, highest amount first
   * within each currency. A category with expenses in more than one currency produces one row
   * per currency rather than summing different currencies' minor units into one meaningless
   * figure — see {@link CategoryBreakdownEntry}.
   */
  public List<CategoryBreakdownEntry> categoryBreakdown(UUID organizationId, LocalDate monthStart, LocalDate monthEnd) {
    return jdbcTemplate.query(
        "SELECT c.id AS category_id, c.name AS category_name, e.currency AS currency, "
            + "COALESCE(SUM(e.amount_minor), 0) AS total "
            + "FROM category c "
            + "JOIN expense e ON e.category_id = c.id AND e.status = 'POSTED' "
            + "AND e.created_at >= ? AND e.created_at < ? "
            + "WHERE c.organization_id = ? "
            + "GROUP BY c.id, c.name, e.currency "
            + "HAVING COALESCE(SUM(e.amount_minor), 0) > 0 "
            + "ORDER BY e.currency, total DESC",
        (rs, rowNum) ->
            new CategoryBreakdownEntry(
                (UUID) rs.getObject("category_id"),
                rs.getString("category_name"),
                rs.getString("currency"),
                rs.getLong("total")),
        startOfDayUtc(monthStart),
        startOfDayUtc(monthEnd),
        organizationId);
  }

  /**
   * POSTED spend per month, per currency, for the given contiguous range of months. Every
   * currency that appears anywhere in the window gets one complete series across every month in
   * {@code months} — a month with no POSTED spend in a currency that has spend elsewhere in the
   * window is an explicit zero row for that currency, not a missing entry, so the caller never
   * has to guess which currencies exist. The caller supplies the month list so this method has no
   * month-count assumption baked in.
   */
  public List<MonthlySpend> monthlySeries(UUID organizationId, List<YearMonth> months) {
    if (months.isEmpty()) {
      return List.of();
    }
    YearMonth earliest = months.get(0);
    YearMonth latest = months.get(months.size() - 1);
    // date_trunc('month', ts AT TIME ZONE 'UTC') returns a timezone-less timestamp holding the
    // correct UTC wall-clock value, but a plain timestamp column has no zone attached -- the
    // JDBC driver's getTimestamp() interprets it in the JVM's default zone, not UTC. On a host
    // whose default zone isn't UTC (this one runs at UTC+3), that silently shifted every row's
    // reported month backward by a day near a month boundary. Converting back to timestamptz
    // (AT TIME ZONE 'UTC' a second time) makes the driver read it as the UTC instant it is.
    List<Object[]> rows =
        jdbcTemplate.query(
            "SELECT (date_trunc('month', created_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') AS month, "
                + "currency, SUM(amount_minor) AS total "
                + "FROM expense "
                + "WHERE organization_id = ? AND status = 'POSTED' "
                + "AND created_at >= ? AND created_at < ? "
                + "GROUP BY date_trunc('month', created_at AT TIME ZONE 'UTC'), currency",
            (rs, rowNum) ->
                new Object[] {
                  YearMonth.from(rs.getTimestamp("month").toInstant().atZone(ZoneOffset.UTC)),
                  rs.getString("currency"),
                  rs.getLong("total")
                },
            organizationId,
            startOfMonthUtc(earliest),
            startOfMonthUtc(latest.plusMonths(1)));

    Map<String, Map<YearMonth, Long>> byCurrencyThenMonth = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String currency = (String) row[1];
      byCurrencyThenMonth
          .computeIfAbsent(currency, ignored -> new LinkedHashMap<>())
          .put((YearMonth) row[0], (Long) row[2]);
    }

    List<MonthlySpend> series = new ArrayList<>();
    for (Map.Entry<String, Map<YearMonth, Long>> currencyEntry : byCurrencyThenMonth.entrySet()) {
      String currency = currencyEntry.getKey();
      Map<YearMonth, Long> byMonth = currencyEntry.getValue();
      for (YearMonth month : months) {
        series.add(new MonthlySpend(month, currency, byMonth.getOrDefault(month, 0L)));
      }
    }
    return series;
  }

  public long countByStatus(UUID organizationId, ExpenseStatus status) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense WHERE organization_id = ? AND status = ?",
            Long.class,
            organizationId,
            status.name());
    return count == null ? 0 : count;
  }

  /** Documents that reached one of {@code DocumentStatus}'s terminal statuses since {@code
   * since}. Built from the enum rather than a hand-written literal list so a future rename (as
   * V21 did for {@code NEEDS_REVIEW} -> {@code EXTRACTION_NEEDS_REVIEW}) cannot leave this query
   * silently counting against a status the schema no longer accepts. */
  public long documentsProcessedSince(UUID organizationId, LocalDate since) {
    List<String> terminalStatuses =
        java.util.Arrays.stream(DocumentStatus.values())
            .filter(DocumentStatus::isTerminal)
            .map(Enum::name)
            .toList();
    String placeholders = String.join(", ", java.util.Collections.nCopies(terminalStatuses.size(), "?"));
    Object[] args =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(organizationId),
                java.util.stream.Stream.concat(
                    terminalStatuses.stream(), java.util.stream.Stream.of(startOfDayUtc(since))))
            .toArray();
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document "
                + "WHERE organization_id = ? "
                + "AND status IN (" + placeholders + ") "
                + "AND updated_at >= ?",
            Long.class,
            args);
    return count == null ? 0 : count;
  }

  /**
   * Binds as a UTC instant rather than {@code java.sql.Timestamp.valueOf(LocalDateTime)}, which
   * interprets the local date/time in the JVM's default timezone. Every {@code created_at}/{@code
   * updated_at} column here is {@code TIMESTAMPTZ}, and {@link DashboardService} computes its
   * month/day boundaries against {@code Clock.systemUTC()} — binding through the JVM default zone
   * would silently shift both bounds by the host's UTC offset, misattributing hours of spend
   * across a month or day boundary on any non-UTC host.
   */
  private static java.sql.Timestamp startOfDayUtc(LocalDate date) {
    return java.sql.Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  private static java.sql.Timestamp startOfMonthUtc(YearMonth month) {
    return java.sql.Timestamp.from(Instant.from(month.atDay(1).atStartOfDay(ZoneOffset.UTC)));
  }
}
