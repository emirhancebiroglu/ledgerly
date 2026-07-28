package com.ledgerly.api.budget;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BudgetController {

  private final BudgetService budgetService;

  public BudgetController(BudgetService budgetService) {
    this.budgetService = budgetService;
  }

  @PostMapping("/api/v1/budgets")
  @ResponseStatus(HttpStatus.CREATED)
  public BudgetResponse create(
      @Valid @RequestBody BudgetRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    Budget budget = budgetService.create(request, principal);
    return responseFor(budget);
  }

  @GetMapping("/api/v1/budgets")
  public List<BudgetResponse> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    List<Budget> budgets = budgetService.list(page, size, principal);
    var usageByBudget = budgetService.usageByBudget(budgets);
    return budgets.stream()
        .map(budget -> BudgetResponse.from(budget, usageByBudget.get(budget.getId())))
        .toList();
  }

  @GetMapping("/api/v1/budgets/{id}")
  public BudgetResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    Budget budget = budgetService.get(id, principal);
    return responseFor(budget);
  }

  @PutMapping("/api/v1/budgets/{id}")
  public BudgetResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody BudgetRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    Budget budget = budgetService.update(id, request, principal);
    return responseFor(budget);
  }

  @DeleteMapping("/api/v1/budgets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    budgetService.delete(id, principal);
  }

  private BudgetResponse responseFor(Budget budget) {
    return BudgetResponse.from(
        budget, budgetService.usageByBudget(List.of(budget)).get(budget.getId()));
  }
}
