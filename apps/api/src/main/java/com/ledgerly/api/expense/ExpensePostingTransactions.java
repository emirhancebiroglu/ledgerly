package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.ledger.EntryDirection;
import com.ledgerly.api.ledger.LedgerAccountRepository;
import com.ledgerly.api.ledger.LedgerEntry;
import com.ledgerly.api.ledger.LedgerTransaction;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import com.ledgerly.api.ledger.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional writes for {@link ExpensePostingService}.
 *
 * <p>These live in their own bean deliberately — a {@code @Transactional} method invoked via
 * {@code this} from inside the same bean bypasses the Spring proxy entirely and silently runs with
 * no transaction at all, the same trap {@link com.ledgerly.api.document.DocumentStatusTransitions}
 * and the M3 idempotency work both document. {@link ExpensePostingService} injects this type
 * instead of calling these methods on itself.
 */
@Component
public class ExpensePostingTransactions {

  /** Name of the org-scoped liability account every expense credits. Matches the M2 seed data. */
  private static final String LIABILITY_ACCOUNT_NAME = "Accounts Payable";

  private final LedgerAccountRepository ledgerAccountRepository;
  private final LedgerTransactionRepository ledgerTransactionRepository;
  private final ExpenseRepository expenseRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public ExpensePostingTransactions(
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

  @Transactional
  public Expense recordPosted(
      UUID organizationId,
      UUID documentId,
      UUID actor,
      Category category,
      ExtractionProposal proposal,
      CategorizeResponse response) {
    UUID expenseAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, category.getName(), "EXPENSE", proposal.currency());
    UUID liabilityAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, LIABILITY_ACCOUNT_NAME, "LIABILITY", proposal.currency());

    Money amount = Money.of(proposal.totalMinor(), proposal.currency());
    LedgerTransaction transaction =
        LedgerTransaction.post(
            organizationId,
            proposal.currency(),
            Instant.now(),
            List.of(
                LedgerEntry.of(expenseAccountId, EntryDirection.DEBIT, amount, amount, BigDecimal.ONE),
                LedgerEntry.of(
                    liabilityAccountId, EntryDirection.CREDIT, amount, amount, BigDecimal.ONE)));
    ledgerTransactionRepository.save(transaction);

    Expense expense =
        expenseRepository.save(
            Expense.posted(
                organizationId,
                documentId,
                category.getId(),
                transaction.id(),
                proposal.totalMinor(),
                proposal.currency(),
                response.confidence(),
                response.citation()));
    expenseRepository.flush();

    auditService.record(
        organizationId,
        actor,
        "POST",
        "expense",
        expense.getId(),
        null,
        auditPayload(expense),
        CorrelationIds.current());

    return expense;
  }

  @Transactional
  public Expense recordNeedsReview(
      UUID organizationId,
      UUID documentId,
      UUID actor,
      Category category,
      ExtractionProposal proposal,
      CategorizeResponse response) {
    Expense expense =
        expenseRepository.save(
            Expense.needsReview(
                organizationId,
                documentId,
                category.getId(),
                proposal.totalMinor(),
                proposal.currency(),
                response.confidence(),
                response.citation()));
    expenseRepository.flush();

    auditService.record(
        organizationId,
        actor,
        "NEEDS_REVIEW",
        "expense",
        expense.getId(),
        null,
        auditPayload(expense),
        CorrelationIds.current());

    return expense;
  }

  private String auditPayload(Expense expense) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "documentId", expense.getDocumentId(),
              "categoryId", expense.getCategoryId(),
              "amountMinor", expense.getAmountMinor(),
              "currency", expense.getCurrency(),
              "confidence", expense.getCategorizationConfidence(),
              "status", expense.getStatus().name()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize expense for audit trail", e);
    }
  }
}
