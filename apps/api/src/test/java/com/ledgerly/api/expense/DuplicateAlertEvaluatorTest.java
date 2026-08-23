package com.ledgerly.api.expense;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.alert.AlertRepository;
import com.ledgerly.api.audit.AuditService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link DuplicateAlertEvaluator#evaluate}: a genuine exception from {@link DuplicateMatcher}
 * never propagates outward — the plan's "a failure inside detection does not roll back or block
 * the expense posting" guarantee, exercised directly rather than through a real integration
 * failure mode that would be awkward to fabricate against a live Postgres connection. */
@ExtendWith(MockitoExtension.class)
class DuplicateAlertEvaluatorTest {

  @Mock private DuplicateMatcher duplicateMatcher;
  @Mock private AlertRepository alertRepository;
  @Mock private AuditService auditService;

  private DuplicateAlertEvaluator evaluator;

  @Test
  void aRuntimeExceptionFromTheMatcherIsSwallowedNotPropagated() {
    evaluator = new DuplicateAlertEvaluator(duplicateMatcher, alertRepository, auditService, new ObjectMapper());
    UUID orgId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    Expense expense =
        Expense.posted(
            orgId, UUID.randomUUID(), "Acme Corp", categoryId, null, 10_000L, "EUR", 0.95, null,
            "INV-1", java.time.LocalDate.of(2026, 7, 20));
    when(alertRepository.existsByExpenseIdAndAlertType(any(), any())).thenReturn(false);
    when(duplicateMatcher.findDuplicate(expense)).thenThrow(new IllegalStateException("boom"));

    assertThatCode(() -> evaluator.evaluate(expense, UUID.randomUUID())).doesNotThrowAnyException();

    verifyNoInteractions(auditService);
  }
}
