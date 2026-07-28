package com.ledgerly.api.budget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.category.CategoryRepository;
import com.ledgerly.api.correlation.CorrelationIds;
import java.util.Currency;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final String UNIQUE_CONSTRAINT = "uq_budget_organization_category_period_currency";

  private final BudgetRepository budgetRepository;
  private final BudgetSpendRepository budgetSpendRepository;
  private final CategoryRepository categoryRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public BudgetService(
      BudgetRepository budgetRepository,
      BudgetSpendRepository budgetSpendRepository,
      CategoryRepository categoryRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.budgetRepository = budgetRepository;
    this.budgetSpendRepository = budgetSpendRepository;
    this.categoryRepository = categoryRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Budget create(BudgetRequest request, AuthenticatedPrincipal principal) {
    validateRequestReferences(request, principal);
    rejectDuplicate(request, principal, null);

    Budget budget;
    try {
      budget =
          budgetRepository.save(
              new Budget(
                  principal.organizationId(),
                  request.categoryId(),
                  request.period(),
                  request.limitMinor(),
                  request.currency()));
      budgetRepository.flush();
    } catch (DataIntegrityViolationException exception) {
      throwIfDuplicateBudget(exception);
      throw exception;
    }

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "CREATE",
        "budget",
        budget.getId(),
        null,
        auditPayload(budget),
        CorrelationIds.current());
    return budget;
  }

  @Transactional(readOnly = true)
  public List<Budget> list(int page, int size, AuthenticatedPrincipal principal) {
    Pageable pageable =
        PageRequest.of(validatedPage(page), validatedSize(size), Sort.by("period").ascending().and(Sort.by("id")));
    return budgetRepository.findByOrganizationId(principal.organizationId(), pageable);
  }

  @Transactional(readOnly = true)
  public Budget get(UUID id, AuthenticatedPrincipal principal) {
    return findForOrganization(id, principal);
  }

  /** One batch aggregate query supplies usage for a whole bounded budget page. */
  @Transactional(readOnly = true)
  public Map<UUID, BudgetUsage> usageByBudget(List<Budget> budgets) {
    Map<UUID, Long> spentMinorByBudget = new HashMap<>();
    budgets.forEach(budget -> spentMinorByBudget.put(budget.getId(), 0L));
    spentMinorByBudget.putAll(budgetSpendRepository.spentMinorByBudget(budgets));
    return budgets.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                Budget::getId,
                budget ->
                    BudgetUsage.of(
                        spentMinorByBudget.getOrDefault(budget.getId(), 0L),
                        budget.getLimitMinor())));
  }

  @Transactional
  public Budget update(UUID id, BudgetRequest request, AuthenticatedPrincipal principal) {
    Budget budget = findForOrganization(id, principal);
    validateRequestReferences(request, principal);
    rejectDuplicate(request, principal, id);

    String before = auditPayload(budget);
    budget.update(request.categoryId(), request.period(), request.limitMinor(), request.currency());
    try {
      budgetRepository.flush();
    } catch (DataIntegrityViolationException exception) {
      throwIfDuplicateBudget(exception);
      throw exception;
    }

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "UPDATE",
        "budget",
        budget.getId(),
        before,
        auditPayload(budget),
        CorrelationIds.current());
    return budget;
  }

  @Transactional
  public void delete(UUID id, AuthenticatedPrincipal principal) {
    Budget budget = findForOrganization(id, principal);
    String before = auditPayload(budget);
    budgetRepository.delete(budget);
    budgetRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "DELETE",
        "budget",
        budget.getId(),
        before,
        null,
        CorrelationIds.current());
  }

  private void validateRequestReferences(BudgetRequest request, AuthenticatedPrincipal principal) {
    categoryRepository
        .findByIdAndOrganizationId(request.categoryId(), principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Category not found: " + request.categoryId()));
    try {
      Currency.getInstance(request.currency());
    } catch (IllegalArgumentException exception) {
      throw new InvalidBudgetRequestException("Unknown ISO-4217 currency: " + request.currency());
    }
  }

  private void rejectDuplicate(
      BudgetRequest request, AuthenticatedPrincipal principal, UUID existingBudgetId) {
    boolean duplicate =
        existingBudgetId == null
            ? budgetRepository.existsByOrganizationIdAndCategoryIdAndPeriodAndCurrency(
                principal.organizationId(), request.categoryId(), request.period(), request.currency())
            : budgetRepository.existsByOrganizationIdAndCategoryIdAndPeriodAndCurrencyAndIdNot(
                principal.organizationId(),
                request.categoryId(),
                request.period(),
                request.currency(),
                existingBudgetId);
    if (duplicate) {
      throw new DuplicateBudgetException();
    }
  }

  private Budget findForOrganization(UUID id, AuthenticatedPrincipal principal) {
    return budgetRepository
        .findByIdAndOrganizationId(id, principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Budget not found: " + id));
  }

  private int validatedPage(int page) {
    if (page < 0) {
      throw new InvalidBudgetRequestException("page must not be negative: " + page);
    }
    return page;
  }

  private int validatedSize(int size) {
    if (size <= 0) {
      throw new InvalidBudgetRequestException("size must be positive: " + size);
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private void throwIfDuplicateBudget(DataIntegrityViolationException exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof PSQLException postgresException
          && "23505".equals(postgresException.getSQLState())
          && postgresException.getServerErrorMessage() != null
          && UNIQUE_CONSTRAINT.equals(postgresException.getServerErrorMessage().getConstraint())) {
        throw new DuplicateBudgetException();
      }
      current = current.getCause();
    }
  }

  private String auditPayload(Budget budget) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "categoryId", budget.getCategoryId(),
              "period", budget.getPeriod(),
              "limitMinor", budget.getLimitMinor(),
              "currency", budget.getCurrency()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize budget for audit trail", exception);
    }
  }
}
