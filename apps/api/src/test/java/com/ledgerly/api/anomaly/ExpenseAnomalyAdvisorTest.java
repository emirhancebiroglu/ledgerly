package com.ledgerly.api.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledgerly.api.expense.Expense;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpenseAnomalyAdvisorTest {

  @Test
  void acceptsOnlyAResponseWhoseDeterministicFactsMatchTheLoadedContext() {
    UUID expenseId = UUID.randomUUID();
    Expense expense = expense(expenseId);
    AnomalyContextLoader loader = mock(AnomalyContextLoader.class);
    AnomalyClient client = mock(AnomalyClient.class);
    when(loader.load(any(), any()))
        .thenReturn(
            new AnomalyContext(
                List.of(
                    new AnomalyHistoryEntry(100, Instant.parse("2026-07-01T00:00:00Z")),
                    new AnomalyHistoryEntry(200, Instant.parse("2026-07-02T00:00:00Z"))),
                new AnomalyBudgetSnapshot("2026-07", 1_000, 500)));
    when(client.assess(any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(response(expenseId, 3.0, 0.5));

    assertThat(new ExpenseAnomalyAdvisor(loader, client).assess(expense, Instant.now())).isPresent();
  }

  @Test
  void mismatchedOrNonFiniteFactsDegradeToNoAdvisory() {
    UUID expenseId = UUID.randomUUID();
    Expense expense = expense(expenseId);
    AnomalyContextLoader loader = mock(AnomalyContextLoader.class);
    AnomalyClient client = mock(AnomalyClient.class);
    when(loader.load(any(), any()))
        .thenReturn(new AnomalyContext(List.of(), null));
    when(client.assess(any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(response(UUID.randomUUID(), 0.0, null));

    assertThat(new ExpenseAnomalyAdvisor(loader, client).assess(expense, Instant.now())).isEmpty();
  }

  @Test
  void nullContractFieldsDoNotDefaultIntoAnAdvisory() {
    UUID expenseId = UUID.randomUUID();
    Expense expense = expense(expenseId);
    AnomalyContextLoader loader = mock(AnomalyContextLoader.class);
    AnomalyClient client = mock(AnomalyClient.class);
    when(loader.load(any(), any())).thenReturn(new AnomalyContext(List.of(), null));
    when(client.assess(any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(
            "{\"expense_id\":\""
                + expenseId
                + "\",\"risk\":null,\"history_count\":null,\"z_score\":null,"
                + "\"budget_burn_rate\":null,\"explanation\":\"Outlier\",\"model\":\"test\"}");

    assertThat(new ExpenseAnomalyAdvisor(loader, client).assess(expense, Instant.now())).isEmpty();
  }

  @Test
  void acceptsAContractValidNoBudgetResponse() {
    UUID expenseId = UUID.randomUUID();
    Expense expense = expense(expenseId);
    AnomalyContextLoader loader = mock(AnomalyContextLoader.class);
    AnomalyClient client = mock(AnomalyClient.class);
    when(loader.load(any(), any())).thenReturn(new AnomalyContext(List.of(), null));
    when(client.assess(any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(
            "{\"expense_id\":\""
                + expenseId
                + "\",\"risk\":\"LOW\",\"history_count\":0,\"z_score\":0.0,"
                + "\"budget_burn_rate\":null,\"explanation\":\"Typical pattern\",\"model\":\"test\"}");

    assertThat(new ExpenseAnomalyAdvisor(loader, client).assess(expense, Instant.now())).isPresent();
  }

  private Expense expense(UUID expenseId) {
    Expense expense = mock(Expense.class);
    when(expense.getId()).thenReturn(expenseId);
    when(expense.getOrganizationId()).thenReturn(UUID.randomUUID());
    when(expense.getCategoryId()).thenReturn(UUID.randomUUID());
    when(expense.getCurrency()).thenReturn("EUR");
    when(expense.getAmountMinor()).thenReturn(300L);
    return expense;
  }

  private String response(UUID expenseId, double zScore, Double burnRate) {
    String burn = burnRate == null ? "null" : burnRate.toString();
    return "{\"expense_id\":\""
        + expenseId
        + "\",\"risk\":\"HIGH\",\"history_count\":2,\"z_score\":"
        + zScore
        + ",\"budget_burn_rate\":"
        + burn
        + ",\"explanation\":\"Outlier\",\"model\":\"test\"}";
  }
}
