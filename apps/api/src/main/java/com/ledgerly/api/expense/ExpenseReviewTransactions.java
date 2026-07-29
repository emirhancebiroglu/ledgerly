package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.budget.BudgetThresholdEvaluator;
import com.ledgerly.api.anomaly.ExpensePostedEvent;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.ledger.LedgerAccountRepository;
import com.ledgerly.api.ledger.LedgerTransaction;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional write behind {@code POST /expenses/{id}/approve} and {@code .../correct}:
 * builds the balanced ledger transaction a {@code NEEDS_REVIEW} expense never got, then resolves
 * the expense to {@code POSTED}.
 *
 * <p>A separate bean from {@link ExpensePostingTransactions} deliberately — not because the
 * write shape differs enough to justify it, but because {@link ExpenseReviewService} needs an
 * injectable {@code @Transactional} target and calling into a bean via {@code this} from the same
 * class silently drops the transaction (documented on {@link ExpensePostingTransactions}, and the
 * cause of three prior bugs in this codebase).
 */
@Component
public class ExpenseReviewTransactions {

  private static final String LIABILITY_ACCOUNT_NAME = "Accounts Payable";

  private final LedgerAccountRepository ledgerAccountRepository;
  private final LedgerTransactionRepository ledgerTransactionRepository;
  private final ExpenseRepository expenseRepository;
  private final BudgetThresholdEvaluator budgetThresholdEvaluator;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public ExpenseReviewTransactions(
      LedgerAccountRepository ledgerAccountRepository,
      LedgerTransactionRepository ledgerTransactionRepository,
      ExpenseRepository expenseRepository,
      BudgetThresholdEvaluator budgetThresholdEvaluator,
      ApplicationEventPublisher eventPublisher,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerTransactionRepository = ledgerTransactionRepository;
    this.expenseRepository = expenseRepository;
    this.budgetThresholdEvaluator = budgetThresholdEvaluator;
    this.eventPublisher = eventPublisher;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /**
   * @param category the category to post against — the AI's original choice for approve, the
   *     human's replacement for correct.
   * @throws ExpenseAlreadyResolvedException if the expense was not {@code NEEDS_REVIEW} when
   *     {@link ExpenseRepository#resolveIfNeedsReview} ran. That single atomic {@code UPDATE ...
   *     WHERE status = 'NEEDS_REVIEW'}, not the read below, is what makes this safe under
   *     concurrent approve/correct calls carrying different {@code Idempotency-Key} values (which
   *     the M3 idempotency filter cannot dedup, since different keys are different claims): only
   *     one such statement can ever match a given row, so exactly one caller's update takes
   *     effect no matter how two requests interleave — Postgres re-evaluates the {@code WHERE}
   *     against the freshly committed row before a blocked writer proceeds. A prior version of
   *     this method used {@code SELECT ... FOR UPDATE} plus an in-memory status check instead,
   *     and a real concurrency test proved that insufficient: the check ran against an {@code
   *     Expense} instance already resident in this method's persistence context from the read
   *     below, not a fresh read, so the lock alone did not stop two callers from both seeing
   *     {@code NEEDS_REVIEW}. Folding the guard into the write itself removes any dependency on
   *     persistence-context freshness.
   *
   *     <p>The ledger transaction is still built and saved before this check runs. That INSERT is
   *     not optional to roll back on a loss — a committed, unreferenced transaction row is a real
   *     row {@code ledger_entry}'s deferred balance trigger and any account-balance query would
   *     see, not a cosmetic loose end — so it depends on this whole method staying
   *     {@code @Transactional} and {@link ExpenseAlreadyResolvedException} staying unchecked, so
   *     Spring's default rollback rule actually fires.
   */
  @Transactional
  public Expense resolve(
      UUID organizationId, UUID expenseId, UUID actor, Category category, String action) {
    Expense expense =
        expenseRepository
            .findByIdAndOrganizationId(expenseId, organizationId)
            .orElseThrow(() -> new NoSuchElementException("Expense not found: " + expenseId));

    String before = auditPayload(expense);

    UUID expenseAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, category.getName(), "EXPENSE", expense.getCurrency());
    UUID liabilityAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, LIABILITY_ACCOUNT_NAME, "LIABILITY", expense.getCurrency());

    Instant postedAt = Instant.now();
    LedgerTransaction transaction =
        LedgerTransaction.post(
            organizationId,
            expense.getCurrency(),
            postedAt,
            ExpenseLedgerEntries.forSignedAmount(
                expenseAccountId,
                liabilityAccountId,
                expense.getAmountMinor(),
                expense.getCurrency()));
    ledgerTransactionRepository.save(transaction);

    int rowsResolved =
        expenseRepository.resolveIfNeedsReview(
            expenseId, organizationId, category.getId(), transaction.id());
    if (rowsResolved == 0) {
      throw new ExpenseAlreadyResolvedException(expenseId);
    }

    Expense resolved =
        expenseRepository
            .findByIdAndOrganizationId(expenseId, organizationId)
            .orElseThrow(() -> new NoSuchElementException("Expense not found: " + expenseId));

    budgetThresholdEvaluator.evaluate(resolved, postedAt, actor);
    eventPublisher.publishEvent(new ExpensePostedEvent(organizationId, resolved.getId(), postedAt, actor));

    auditService.record(
        organizationId,
        actor,
        action,
        "expense",
        resolved.getId(),
        before,
        auditPayload(resolved),
        CorrelationIds.current());

    return resolved;
  }

  private String auditPayload(Expense expense) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("documentId", expense.getDocumentId());
    payload.put("vendor", expense.getVendor());
    payload.put("categoryId", expense.getCategoryId());
    payload.put("ledgerTransactionId", expense.getLedgerTransactionId());
    payload.put("amountMinor", expense.getAmountMinor());
    payload.put("currency", expense.getCurrency());
    payload.put("status", expense.getStatus().name());
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize expense for audit trail", e);
    }
  }
}
