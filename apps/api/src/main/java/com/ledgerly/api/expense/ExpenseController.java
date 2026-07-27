package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExpenseController {

  private final ExpenseStubService expenseStubService;
  private final ExpenseService expenseService;

  public ExpenseController(ExpenseStubService expenseStubService, ExpenseService expenseService) {
    this.expenseStubService = expenseStubService;
    this.expenseService = expenseService;
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
    return ExpenseResponse.from(expenseService.get(id, principal));
  }

  /**
   * Filter/sort/search over the caller's org. {@code sort} is {@code (date|amount),(asc|desc)},
   * e.g. {@code amount,desc}; defaults to {@code date,desc}. An unrecognized {@code status} or
   * {@code sort} value is a 400, not a silently-ignored parameter or a 500 from an invalid
   * {@code ORDER BY} column.
   */
  @GetMapping("/api/v1/expenses")
  public List<ExpenseResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    ExpenseListQuery query = ExpenseListQuery.parse(status, search, sort);
    return expenseService.list(query, page, size, principal).stream()
        .map(ExpenseResponse::from)
        .toList();
  }
}
