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
   * @param expenseId re-fetched and locked inside this transaction rather than trusting the
   *     caller's already-loaded {@link Expense} — the NEEDS_REVIEW check in {@link
   *     Expense#resolve} must run against the current committed row, not a copy that might be
   *     stale under concurrent approval attempts.
   * @param category the category to post against — the AI's original choice for approve, the
   *     human's replacement for correct.
   */
  @Transactional
  public Expense resolve(
      UUID organizationId, UUID expenseId, UUID actor, Category category, String action) {
    Expense expense =
        expenseRepository
            .findByIdAndOrganizationId(expenseId, organizationId)
            .orElseThrow(() -> new NoSuchElementException("Expense not found: " + expenseId));
    if (expense.getStatus() != ExpenseStatus.NEEDS_REVIEW) {
      throw new ExpenseAlreadyResolvedException(expenseId);
    }

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

    expense.resolve(category.getId(), transaction.id());
    expenseRepository.flush();

    auditService.record(
        organizationId,
        actor,
        action,
        "expense",
        expense.getId(),
        before,
        auditPayload(expense),
        CorrelationIds.current());

    return expense;
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
