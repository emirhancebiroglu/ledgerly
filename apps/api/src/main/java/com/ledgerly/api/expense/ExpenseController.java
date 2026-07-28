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
  private final ExpenseReviewService expenseReviewService;

  public ExpenseController(
      ExpenseStubService expenseStubService,
      ExpenseService expenseService,
      ExpenseReviewService expenseReviewService) {
    this.expenseStubService = expenseStubService;
    this.expenseService = expenseService;
    this.expenseReviewService = expenseReviewService;
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

  /** {@link #get} plus ledger entries and document metadata, for the expense-detail screen. */
  @GetMapping("/api/v1/expenses/{id}/detail")
  public ExpenseDetailResponse getDetail(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return expenseService.getDetail(id, principal);
  }

  /**
   * Filter/sort/search over the caller's org. {@code sort} is {@code (date|amount),(asc|desc)},
   * e.g. {@code amount,desc}; defaults to {@code date,desc}. An unrecognized {@code status} or
   * {@code sort} value, a negative {@code page}, or a non-positive {@code size} is a 400, not a
   * silently-ignored parameter or a 500 from an invalid {@code ORDER BY} column or page request.
   *
   * <p>{@code search} matches against {@code vendor}; an expense posted before the V13 migration
   * has no vendor backfilled (its vendor lived only in {@code document.proposal} JSONB, never a
   * queryable column) and so is unreachable via {@code search} until re-posted.
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

  /**
   * Approve a review-queue item using the category the AI already chose — posts the balanced
   * ledger transaction that categorization withheld. 409 if the expense is not currently {@code
   * NEEDS_REVIEW} (already approved/corrected, including via a replayed request outside the
   * {@code Idempotency-Key} window).
   */
  @PostMapping("/api/v1/expenses/{id}/approve")
  public ExpenseResponse approve(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ExpenseResponse.from(expenseReviewService.approve(id, principal));
  }

  /**
   * Resolve a review-queue item into a different category than the AI chose, then post. Same 409
   * semantics as {@link #approve}.
   */
  @PostMapping("/api/v1/expenses/{id}/correct")
  public ExpenseResponse correct(
      @PathVariable UUID id,
      @Valid @RequestBody CorrectExpenseRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ExpenseResponse.from(
        expenseReviewService.correct(id, request.categoryId(), principal));
  }
}
