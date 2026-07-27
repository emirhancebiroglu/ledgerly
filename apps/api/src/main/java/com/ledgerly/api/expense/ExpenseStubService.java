package com.ledgerly.api.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseStubService {

  private final ExpenseStubRepository expenseStubRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public ExpenseStubService(
      ExpenseStubRepository expenseStubRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.expenseStubRepository = expenseStubRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ExpenseStubResponse create(CreateExpenseRequest request, AuthenticatedPrincipal principal) {
    ExpenseStub saved =
        expenseStubRepository.save(
            new ExpenseStub(principal.organizationId(), request.amountMinor(), request.currency()));
    expenseStubRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "CREATE",
        "expense_stub",
        saved.getId(),
        null,
        toJson(saved),
        CorrelationIds.current());

    return ExpenseStubResponse.from(saved);
  }

  private String toJson(ExpenseStub expense) {
    try {
      return objectMapper.writeValueAsString(ExpenseStubResponse.from(expense));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize expense for audit trail", e);
    }
  }
}
