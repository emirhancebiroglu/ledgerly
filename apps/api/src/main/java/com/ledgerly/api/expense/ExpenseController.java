package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExpenseController {

  private final ExpenseStubService expenseStubService;

  public ExpenseController(ExpenseStubService expenseStubService) {
    this.expenseStubService = expenseStubService;
  }

  @PostMapping("/api/v1/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseStubResponse create(
      @Valid @RequestBody CreateExpenseRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return expenseStubService.create(request, principal);
  }
}
