package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.document.Document;
import com.ledgerly.api.document.DocumentRepository;
import com.ledgerly.api.document.DocumentResponse;
import com.ledgerly.api.document.ExtractionProposal;
import com.ledgerly.api.document.MalformedProposalException;
import com.ledgerly.api.document.ProposalMapper;
import com.ledgerly.api.expense.ExpenseListQuery.ExpenseSortField;
import com.ledgerly.api.ledger.LedgerEntryView;
import com.ledgerly.api.ledger.LedgerTransactionRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to {@link Expense}, org-scoped. Mutations live in {@link ExpensePostingService}. */
@Service
public class ExpenseService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ExpenseRepository expenseRepository;
  private final DocumentRepository documentRepository;
  private final LedgerTransactionRepository ledgerTransactionRepository;
  private final ProposalMapper proposalMapper;

  public ExpenseService(
      ExpenseRepository expenseRepository,
      DocumentRepository documentRepository,
      LedgerTransactionRepository ledgerTransactionRepository,
      ProposalMapper proposalMapper) {
    this.expenseRepository = expenseRepository;
    this.documentRepository = documentRepository;
    this.ledgerTransactionRepository = ledgerTransactionRepository;
    this.proposalMapper = proposalMapper;
  }

  @Transactional(readOnly = true)
  public Expense get(UUID id, AuthenticatedPrincipal principal) {
    return expenseRepository
        .findByIdAndOrganizationId(id, principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Expense not found: " + id));
  }

  /**
   * {@code get} plus its ledger entries and document metadata. A {@code NEEDS_REVIEW} expense has
   * no {@code ledgerTransactionId} yet, so its entry list is empty rather than a lookup that would
   * otherwise NPE on a null id.
   */
  @Transactional(readOnly = true)
  public ExpenseDetailResponse getDetail(UUID id, AuthenticatedPrincipal principal) {
    Expense expense = get(id, principal);

    List<LedgerEntryView> ledgerEntries =
        expense.getLedgerTransactionId() == null
            ? List.of()
            : ledgerTransactionRepository.findEntriesByTransactionId(
                expense.getLedgerTransactionId(), principal.organizationId());

    Document document =
        documentRepository
            .findByIdAndOrganizationId(expense.getDocumentId(), principal.organizationId())
            .orElseThrow(
                () -> new NoSuchElementException("Document not found: " + expense.getDocumentId()));

    return ExpenseDetailResponse.from(
        expense,
        ledgerEntries,
        DocumentResponse.from(document),
        extractedFields(document));
  }

  private ExpenseDetailResponse.ExtractedDocumentFields extractedFields(Document document) {
    if (document.getProposal() == null) {
      return ExpenseDetailResponse.ExtractedDocumentFields.unavailable();
    }
    try {
      ExtractionProposal proposal = proposalMapper.parse(document.getProposal());
      return new ExpenseDetailResponse.ExtractedDocumentFields(
          proposal.invoiceNumber(),
          proposal.documentDate(),
          Long.toString(proposal.taxMinor()));
    } catch (MalformedProposalException ignored) {
      // Legacy rows can predate a persisted, schema-valid proposal. Their core expense data is
      // still usable; exposing fabricated extracted fields would be less honest than leaving them empty.
      return ExpenseDetailResponse.ExtractedDocumentFields.unavailable();
    }
  }

  @Transactional(readOnly = true)
  public List<Expense> list(
      ExpenseListQuery query, int page, int size, AuthenticatedPrincipal principal) {
    Pageable pageable = PageRequest.of(validatedPage(page), validatedSize(size), sortFor(query));
    UUID organizationId = principal.organizationId();

    if (query.status() != null && query.search() != null) {
      return expenseRepository.findByOrganizationIdAndStatusAndVendorIgnoreCaseContaining(
          organizationId, query.status(), query.search(), pageable);
    }
    if (query.status() != null) {
      return expenseRepository.findByOrganizationIdAndStatus(
          organizationId, query.status(), pageable);
    }
    if (query.search() != null) {
      return expenseRepository.findByOrganizationIdAndVendorIgnoreCaseContaining(
          organizationId, query.search(), pageable);
    }
    return expenseRepository.findByOrganizationId(organizationId, pageable);
  }

  private int validatedPage(int page) {
    if (page < 0) {
      throw new InvalidExpenseListQueryException("page must not be negative: " + page);
    }
    return page;
  }

  private int validatedSize(int size) {
    if (size <= 0) {
      throw new InvalidExpenseListQueryException("size must be positive: " + size);
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private Sort sortFor(ExpenseListQuery query) {
    String property =
        query.sortField() == ExpenseSortField.AMOUNT_MINOR ? "amountMinor" : "createdAt";
    return Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, property);
  }
}
