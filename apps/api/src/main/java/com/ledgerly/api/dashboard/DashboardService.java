package com.ledgerly.api.dashboard;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private static final int SERIES_MONTHS = 6;

  private final DashboardRepository dashboardRepository;
  private final Clock clock;

  public DashboardService(DashboardRepository dashboardRepository, Clock clock) {
    this.dashboardRepository = dashboardRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DashboardSummaryResponse summary(AuthenticatedPrincipal principal) {
    var organizationId = principal.organizationId();
    LocalDate today = LocalDate.now(clock);
    YearMonth currentMonth = YearMonth.from(today);
    YearMonth previousMonth = currentMonth.minusMonths(1);

    List<CurrencyTotal> totalsThisMonth =
        dashboardRepository.totalsByCurrency(
            organizationId, currentMonth.atDay(1), currentMonth.plusMonths(1).atDay(1));
    List<CurrencyTotal> totalsLastMonth =
        dashboardRepository.totalsByCurrency(
            organizationId, previousMonth.atDay(1), currentMonth.atDay(1));
    List<CategoryBreakdownEntry> categoryBreakdown =
        dashboardRepository.categoryBreakdown(
            organizationId, currentMonth.atDay(1), currentMonth.plusMonths(1).atDay(1));
    List<MonthlySpend> monthlySeries =
        dashboardRepository.monthlySeries(organizationId, trailingMonths(currentMonth));
    long reviewQueueCount = dashboardRepository.countByStatus(organizationId, "NEEDS_REVIEW");
    long documentsProcessedToday = dashboardRepository.documentsProcessedSince(organizationId, today);

    return new DashboardSummaryResponse(
        totalsThisMonth,
        totalsLastMonth,
        categoryBreakdown,
        monthlySeries,
        reviewQueueCount,
        documentsProcessedToday);
  }

  private List<YearMonth> trailingMonths(YearMonth currentMonth) {
    List<YearMonth> months = new ArrayList<>(SERIES_MONTHS);
    for (int i = SERIES_MONTHS - 1; i >= 0; i--) {
      months.add(currentMonth.minusMonths(i));
    }
    return months;
  }
}
