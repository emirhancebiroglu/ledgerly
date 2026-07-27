package com.ledgerly.api.expense;

import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.expense.ExpenseListQuery.ExpenseSortField;
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

  public ExpenseService(ExpenseRepository expenseRepository) {
    this.expenseRepository = expenseRepository;
  }

  @Transactional(readOnly = true)
  public Expense get(UUID id, AuthenticatedPrincipal principal) {
    return expenseRepository
        .findByIdAndOrganizationId(id, principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Expense not found: " + id));
  }

  @Transactional(readOnly = true)
  public List<Expense> list(
      ExpenseListQuery query, int page, int size, AuthenticatedPrincipal principal) {
    Pageable pageable = PageRequest.of(page, boundedSize(size), sortFor(query));
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

  private int boundedSize(int size) {
    if (size <= 0) {
      return 20;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private Sort sortFor(ExpenseListQuery query) {
    String property =
        query.sortField() == ExpenseSortField.AMOUNT_MINOR ? "amountMinor" : "createdAt";
    return Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, property);
  }
}
