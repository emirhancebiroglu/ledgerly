package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExpenseController {

  private final ExpenseStubService expenseStubService;
  private final ExpenseRepository expenseRepository;

  public ExpenseController(
      ExpenseStubService expenseStubService, ExpenseRepository expenseRepository) {
    this.expenseStubService = expenseStubService;
    this.expenseRepository = expenseRepository;
  }

  @PostMapping("/api/v1/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseStubResponse create(
      @Valid @RequestBody CreateExpenseRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return expenseStubService.create(request, principal);
  }

  /** The M6 expense produced by the categorization pipeline — org-scoped, like every other read. */
  @GetMapping("/api/v1/expenses/{id}")
  public ExpenseResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    Expense expense =
        expenseRepository
            .findByIdAndOrganizationId(id, principal.organizationId())
            .orElseThrow(() -> new NoSuchElementException("Expense not found: " + id));
    return ExpenseResponse.from(expense);
  }
}
