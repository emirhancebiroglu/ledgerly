package com.ledgerly.api.alert;

import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.expense.ExpenseRepository;
import org.springframework.stereotype.Component;

/** Builds the short, digit-free headline for an alert card. Currency amounts are never embedded
 * here — the repo's rule (see {@code formatMoney} in the web client) is that money is formatted
 * for display only in the browser, so a title naming an exact figure would create a second,
 * backend-side formatting path. The full sentence (with formatted amounts) is assembled by the
 * client from this title plus the response's existing raw fields. */
@Component
public class AlertTitleResolver {

  private final CategoryRepository categoryRepository;
  private final ExpenseRepository expenseRepository;

  public AlertTitleResolver(CategoryRepository categoryRepository, ExpenseRepository expenseRepository) {
    this.categoryRepository = categoryRepository;
    this.expenseRepository = expenseRepository;
  }

  public String resolve(Alert alert) {
    return switch (alert.getAlertType()) {
      case "BUDGET_THRESHOLD" -> budgetThresholdTitle(alert);
      case "ANOMALY_HIGH" -> "Unusual spending detected";
      case "LOW_CONFIDENCE" -> "Low-confidence categorization needs review";
      case "DUPLICATE_SUSPECTED" -> duplicateSuspectedTitle(alert);
      default -> throw new IllegalStateException("Unknown alert type: " + alert.getAlertType());
    };
  }

  private String duplicateSuspectedTitle(Alert alert) {
    String vendor =
        expenseRepository
            .findByIdAndOrganizationId(alert.getExpenseId(), alert.getOrganizationId())
            .map(com.ledgerly.api.expense.Expense::getVendor)
            .filter(v -> v != null && !v.isBlank())
            .orElse("This vendor");
    return "CONFIRMED".equals(alert.getDuplicateTier())
        ? vendor + " may have been billed twice"
        : "Possible duplicate from " + vendor;
  }

  private String budgetThresholdTitle(Alert alert) {
    String categoryName =
        categoryRepository
            .findByIdAndOrganizationId(alert.getCategoryId(), alert.getOrganizationId())
            .map(Category::getName)
            .orElse("Uncategorized");
    int threshold = alert.getThresholdPercent() == null ? 0 : alert.getThresholdPercent();
    return threshold >= 100
        ? categoryName + " budget exceeded"
        : categoryName + " nearing its budget";
  }
}
