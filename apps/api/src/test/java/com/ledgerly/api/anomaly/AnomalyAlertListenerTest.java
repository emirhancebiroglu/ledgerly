package com.ledgerly.api.anomaly;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ledgerly.api.expense.Expense;
import com.ledgerly.api.expense.ExpenseRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnomalyAlertListenerTest {
  @Test void persistsOnlyHighAdvisories() {
    ExpenseRepository expenses = mock(ExpenseRepository.class); ExpenseAnomalyAdvisor advisor = mock(ExpenseAnomalyAdvisor.class);
    AnomalyAlertWriter writer = mock(AnomalyAlertWriter.class); UUID org = UUID.randomUUID(), id = UUID.randomUUID(), actor = UUID.randomUUID();
    Expense expense = mock(Expense.class); when(expenses.findByIdAndOrganizationId(id, org)).thenReturn(Optional.of(expense));
    AnomalyResponse high = new AnomalyResponse(id, AnomalyRisk.HIGH, 0, 0, null, "High", "test");
    when(advisor.assess(expense, Instant.EPOCH)).thenReturn(Optional.of(high));
    new AnomalyAlertListener(expenses, advisor, writer).onPosted(new ExpensePostedEvent(org, id, Instant.EPOCH, actor));
    verify(writer).record(expense, high, Instant.EPOCH, actor);
    when(advisor.assess(expense, Instant.EPOCH)).thenReturn(Optional.of(new AnomalyResponse(id, AnomalyRisk.LOW, 0, 0, null, "Low", "test")));
    new AnomalyAlertListener(expenses, advisor, writer).onPosted(new ExpensePostedEvent(org, id, Instant.EPOCH, actor));
    verifyNoMoreInteractions(writer);
  }
}
