package com.ledgerly.api.anomaly;

import com.ledgerly.api.budget.Budget;
import com.ledgerly.api.budget.BudgetRepository;
import com.ledgerly.api.budget.BudgetSpendRepository;
import com.ledgerly.api.expense.Expense;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional read phase only. The remote AI call happens after this method returns. */
@Service
public class AnomalyContextLoader {

  private final AnomalyHistoryRepository historyRepository;
  private final BudgetRepository budgetRepository;
  private final BudgetSpendRepository budgetSpendRepository;
  private final int historyLimit;

  public AnomalyContextLoader(
      AnomalyHistoryRepository historyRepository,
      BudgetRepository budgetRepository,
      BudgetSpendRepository budgetSpendRepository,
      @Value("${ledgerly.anomaly.history-limit:1000}") int historyLimit) {
    this.historyRepository = historyRepository;
    this.budgetRepository = budgetRepository;
    this.budgetSpendRepository = budgetSpendRepository;
    this.historyLimit = historyLimit;
  }

  @Transactional(readOnly = true)
  public AnomalyContext load(Expense expense, Instant postedAt) {
    List<AnomalyHistoryEntry> history =
        historyRepository.findPostedHistory(
            expense.getOrganizationId(),
            expense.getCategoryId(),
            expense.getCurrency(),
            expense.getId(),
            postedAt,
            historyLimit);
    String period = YearMonth.from(postedAt.atZone(ZoneOffset.UTC)).toString();
    AnomalyBudgetSnapshot budget =
        budgetRepository
            .findByOrganizationIdAndCategoryIdAndPeriodAndCurrency(
                expense.getOrganizationId(), expense.getCategoryId(), period, expense.getCurrency())
            .map(this::snapshot)
            .orElse(null);
    return new AnomalyContext(history, budget);
  }

  private AnomalyBudgetSnapshot snapshot(Budget budget) {
    long spentMinor =
        budgetSpendRepository.spentMinorByBudget(List.of(budget)).getOrDefault(budget.getId(), 0L);
    return new AnomalyBudgetSnapshot(budget.getPeriod(), budget.getLimitMinor(), spentMinor);
  }
}
