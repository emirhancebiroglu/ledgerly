package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.category.CategoryRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Human resolution of a {@code NEEDS_REVIEW} expense. Read access stays on {@link
 * ExpenseService}; this is mutation-only, mirroring the {@link ExpensePostingService}/{@link
 * ExpensePostingTransactions} split for the AI-driven posting path.
 */
@Service
public class ExpenseReviewService {

  private final ExpenseRepository expenseRepository;
  private final CategoryRepository categoryRepository;
  private final ExpenseReviewTransactions transactions;

  public ExpenseReviewService(
      ExpenseRepository expenseRepository,
      CategoryRepository categoryRepository,
      ExpenseReviewTransactions transactions) {
    this.expenseRepository = expenseRepository;
    this.categoryRepository = categoryRepository;
    this.transactions = transactions;
  }

  /** Approve: post using the category the AI already chose. */
  public Expense approve(UUID expenseId, AuthenticatedPrincipal principal) {
    UUID organizationId = principal.organizationId();
    Expense expense = findForOrganization(expenseId, organizationId);
    Category category = findCategory(expense.getCategoryId(), organizationId);
    return transactions.resolve(
        organizationId, expenseId, principal.userId(), category, "APPROVE");
  }

  /** Correct: post using a category the human chose instead of the AI's. */
  public Expense correct(UUID expenseId, UUID categoryId, AuthenticatedPrincipal principal) {
    UUID organizationId = principal.organizationId();
    // Confirms the expense exists in this org before the (potentially more expensive) category
    // lookup, and gives a 404 on the expense id specifically rather than an ambiguous one.
    findForOrganization(expenseId, organizationId);
    Category category = findCategory(categoryId, organizationId);
    return transactions.resolve(
        organizationId, expenseId, principal.userId(), category, "CORRECT");
  }

  private Expense findForOrganization(UUID expenseId, UUID organizationId) {
    return expenseRepository
        .findByIdAndOrganizationId(expenseId, organizationId)
        .orElseThrow(() -> new NoSuchElementException("Expense not found: " + expenseId));
  }

  private Category findCategory(UUID categoryId, UUID organizationId) {
    return categoryRepository
        .findByIdAndOrganizationId(categoryId, organizationId)
        .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
  }
}
