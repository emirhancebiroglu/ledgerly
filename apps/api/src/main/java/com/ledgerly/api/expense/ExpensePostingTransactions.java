package com.ledgerly.api.expense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.budget.BudgetThresholdEvaluator;
import com.ledgerly.api.anomaly.ExpensePostedEvent;
import com.ledgerly.api.category.Category;
import com.ledgerly.api.correlation.CorrelationIds;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.document.DocumentActivityService;
import com.ledgerly.api.document.DocumentActivityStage;
import com.ledgerly.api.ledger.LedgerAccountRepository;
import com.ledgerly.api.ledger.LedgerTransaction;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
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
  private final BudgetThresholdEvaluator budgetThresholdEvaluator;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final DocumentActivityService documentActivityService;

  public ExpensePostingTransactions(
      LedgerAccountRepository ledgerAccountRepository,
      LedgerTransactionRepository ledgerTransactionRepository,
      ExpenseRepository expenseRepository,
      BudgetThresholdEvaluator budgetThresholdEvaluator,
      ApplicationEventPublisher eventPublisher,
      AuditService auditService,
      ObjectMapper objectMapper,
      DocumentActivityService documentActivityService) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerTransactionRepository = ledgerTransactionRepository;
    this.expenseRepository = expenseRepository;
    this.budgetThresholdEvaluator = budgetThresholdEvaluator;
    this.eventPublisher = eventPublisher;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.documentActivityService = documentActivityService;
  }

  @Transactional
  public Expense recordPosted(
      UUID organizationId,
      UUID documentId,
      UUID actor,
      Category category,
      ExtractionProposal proposal,
      CategorizeResponse response) {
    documentActivityService.record(
        documentId, organizationId, DocumentActivityStage.DRAFTING_LEDGER, "Drafting ledger entries");
    UUID expenseAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, category.getName(), "EXPENSE", proposal.currency());
    UUID liabilityAccountId =
        ledgerAccountRepository.findOrCreate(
            organizationId, LIABILITY_ACCOUNT_NAME, "LIABILITY", proposal.currency());

    Instant postedAt = Instant.now();
    LedgerTransaction transaction =
        LedgerTransaction.post(
            organizationId,
            proposal.currency(),
            postedAt,
            ExpenseLedgerEntries.forSignedAmount(
                expenseAccountId, liabilityAccountId, proposal.totalMinor(), proposal.currency()));
    ledgerTransactionRepository.save(transaction);

    Expense expense =
        expenseRepository.save(
            Expense.posted(
                organizationId,
                documentId,
                proposal.vendor(),
                category.getId(),
                transaction.id(),
                proposal.totalMinor(),
                proposal.currency(),
                response.confidence(),
                response.citation()));
    expenseRepository.flush();

    budgetThresholdEvaluator.evaluate(expense, postedAt, actor);
    eventPublisher.publishEvent(new ExpensePostedEvent(organizationId, expense.getId(), postedAt, actor));

    auditService.record(
        organizationId,
        actor,
        "POST",
        "expense",
        expense.getId(),
        null,
        auditPayload(expense),
        CorrelationIds.current());

    documentActivityService.record(
        documentId, organizationId, DocumentActivityStage.POSTED, "Expense posted to the ledger");

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
                proposal.vendor(),
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

    documentActivityService.record(
        documentId,
        organizationId,
        DocumentActivityStage.NEEDS_REVIEW,
        "Expense needs review");

    return expense;
  }

  private String auditPayload(Expense expense) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("documentId", expense.getDocumentId());
    payload.put("vendor", expense.getVendor());
    payload.put("categoryId", expense.getCategoryId());
    payload.put("amountMinor", expense.getAmountMinor());
    payload.put("currency", expense.getCurrency());
    payload.put("confidence", expense.getCategorizationConfidence());
    payload.put("status", expense.getStatus().name());
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize expense for audit trail", e);
    }
  }
}
