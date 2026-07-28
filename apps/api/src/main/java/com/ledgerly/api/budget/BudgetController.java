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
    return BudgetResponse.from(budgetService.create(request, principal));
  }

  @GetMapping("/api/v1/budgets")
  public List<BudgetResponse> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return budgetService.list(page, size, principal).stream().map(BudgetResponse::from).toList();
  }

  @GetMapping("/api/v1/budgets/{id}")
  public BudgetResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return BudgetResponse.from(budgetService.get(id, principal));
  }

  @PutMapping("/api/v1/budgets/{id}")
  public BudgetResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody BudgetRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return BudgetResponse.from(budgetService.update(id, request, principal));
  }

  @DeleteMapping("/api/v1/budgets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    budgetService.delete(id, principal);
  }
}
