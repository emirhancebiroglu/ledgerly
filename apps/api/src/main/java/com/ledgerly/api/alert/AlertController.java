package com.ledgerly.api.alert;

import com.ledgerly.api.alert.AlertResponse.MatchedExpenseSummary;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.auth.CrossOrganizationAccessException;
import com.ledgerly.api.expense.Expense;
import com.ledgerly.api.expense.ExpenseRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertController {

  private static final Set<String> VALID_ALERT_TYPES =
      Set.of("BUDGET_THRESHOLD", "ANOMALY_HIGH", "LOW_CONFIDENCE", "DUPLICATE_SUSPECTED");

  private final AlertRepository alertRepository;
  private final AlertTitleResolver alertTitleResolver;
  private final AlertStateService alertStateService;
  private final ExpenseRepository expenseRepository;

  public AlertController(
      AlertRepository alertRepository,
      AlertTitleResolver alertTitleResolver,
      AlertStateService alertStateService,
      ExpenseRepository expenseRepository) {
    this.alertRepository = alertRepository;
    this.alertTitleResolver = alertTitleResolver;
    this.alertStateService = alertStateService;
    this.expenseRepository = expenseRepository;
  }

  @GetMapping("/api/v1/alerts")
  public List<AlertResponse> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) String type,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    if (type != null && !VALID_ALERT_TYPES.contains(type)) {
      throw new InvalidAlertTypeException("Unknown alert type: " + type);
    }
    List<Alert> alerts =
        alertRepository.findVisible(
            principal.organizationId(), principal.userId(), type, PageRequest.of(page, size));
    Map<UUID, AlertState> stateByAlertId =
        alertStateService.statesFor(
            principal.userId(), alerts.stream().map(Alert::getId).toList());
    return alerts.stream()
        .map(
            alert -> {
              AlertState state = stateByAlertId.get(alert.getId());
              boolean read = state != null && state.getReadAt() != null;
              boolean dismissed = state != null && state.getDismissedAt() != null;
              boolean isDuplicateSuspected = "DUPLICATE_SUSPECTED".equals(alert.getAlertType());
              MatchedExpenseSummary matchedExpense =
                  expenseSummary(alert.getMatchedExpenseId(), principal.organizationId());
              MatchedExpenseSummary triggeringExpense =
                  isDuplicateSuspected
                      ? expenseSummary(alert.getExpenseId(), principal.organizationId())
                      : null;
              return AlertResponse.from(
                  alert, alertTitleResolver.resolve(alert), read, dismissed,
                  matchedExpense, triggeringExpense);
            })
        .toList();
  }

  /** {@code null} when there is no expense to summarize (every non-DUPLICATE_SUSPECTED type) or
   * the expense was since deleted — the card must degrade to a readable state either way, never a
   * broken link or a crash. */
  private MatchedExpenseSummary expenseSummary(UUID expenseId, UUID organizationId) {
    if (expenseId == null) {
      return null;
    }
    return expenseRepository
        .findByIdAndOrganizationId(expenseId, organizationId)
        .map(
            (Expense e) ->
                new MatchedExpenseSummary(e.getVendor(), e.getAmountMinor(), e.getCurrency(), e.getCreatedAt()))
        .orElse(null);
  }

  @GetMapping("/api/v1/alerts/unread-count")
  public UnreadCountResponse unreadCount(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return new UnreadCountResponse(
        alertRepository.countUnread(principal.organizationId(), principal.userId()));
  }

  @PostMapping("/api/v1/alerts/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    alertStateService.markRead(requireOwnedAlert(id, principal.organizationId()), principal.userId());
  }

  @PostMapping("/api/v1/alerts/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllRead(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    alertStateService.markAllRead(principal.organizationId(), principal.userId());
  }

  @PostMapping("/api/v1/alerts/{id}/dismiss")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void dismiss(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    alertStateService.markDismissed(
        requireOwnedAlert(id, principal.organizationId()), principal.userId());
  }

  /** Org-scoped existence check — a foreign organization's alert id must 404, not act on it. */
  private UUID requireOwnedAlert(UUID alertId, UUID organizationId) {
    Alert alert =
        alertRepository
            .findById(alertId)
            .filter(a -> a.getOrganizationId().equals(organizationId))
            .orElseThrow(CrossOrganizationAccessException::new);
    return alert.getId();
  }

  public record UnreadCountResponse(long unreadCount) {}
}
