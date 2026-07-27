package com.ledgerly.api.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.util.UUID;
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
        correlationId());

    return ExpenseStubResponse.from(saved);
  }

  private UUID correlationId() {
    String current = CorrelationIdHolder.current();
    if (current == null) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(current);
    } catch (IllegalArgumentException notAUuid) {
      // A client-supplied X-Correlation-Id need not be a UUID; audit_log.correlation_id is UUID
      // NOT NULL, so derive a stable one deterministically rather than losing the correlation
      // entirely. Trade-off: for a non-UUID header, the audit row's correlation_id will NOT
      // match the literal value in logs or the X-Correlation-Id response header — tracing a
      // support ticket by that literal value back to an audit row needs this same derivation,
      // not a straight string match.
      return UUID.nameUUIDFromBytes(current.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private String toJson(ExpenseStub expense) {
    try {
      return objectMapper.writeValueAsString(ExpenseStubResponse.from(expense));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize expense for audit trail", e);
    }
  }
}
