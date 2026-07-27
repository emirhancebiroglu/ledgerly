package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.ledger.EntryDirection;
import com.ledgerly.api.ledger.LedgerAccountRepository;
import com.ledgerly.api.ledger.LedgerEntry;
import com.ledgerly.api.ledger.LedgerTransaction;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import com.ledgerly.api.ledger.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
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
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public ExpenseReviewTransactions(
      LedgerAccountRepository ledgerAccountRepository,
      LedgerTransactionRepository ledgerTransactionRepository,
      ExpenseRepository expenseRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerTransactionRepository = ledgerTransactionRepository;
    this.expenseRepository = expenseRepository;
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
   *     effect no matter how two requests interleave. A read-then-write split — even guarded by
   *     {@code SELECT ... FOR UPDATE} — does not give this guarantee here, since Hibernate's
   *     locked read can return a Java object holding the pre-transition status to more than one
   *     concurrent transaction depending on lock/commit timing; folding the status guard into the
   *     write itself removes that dependency entirely.
   *
   *     <p>The ledger transaction is still built and saved before this check runs. A losing
   *     caller's transaction row is a harmless orphan — internally balanced, referenced by no
   *     expense, invisible to any account-balance query — rolled back with the rest of this
   *     {@code @Transactional} method rather than left behind, since the throw below aborts the
   *     whole method.
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

    Money amount = Money.of(expense.getAmountMinor(), expense.getCurrency());
    LedgerTransaction transaction =
        LedgerTransaction.post(
            organizationId,
            expense.getCurrency(),
            Instant.now(),
            List.of(
                LedgerEntry.of(expenseAccountId, EntryDirection.DEBIT, amount, amount, BigDecimal.ONE),
                LedgerEntry.of(
                    liabilityAccountId, EntryDirection.CREDIT, amount, amount, BigDecimal.ONE)));
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
