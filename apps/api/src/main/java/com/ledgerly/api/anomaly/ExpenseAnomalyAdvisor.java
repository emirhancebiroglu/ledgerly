package com.ledgerly.api.anomaly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ledgerly.api.expense.Expense;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Loads deterministic input transactionally, then asks AI outside that transaction for advice. */
@Service
public class ExpenseAnomalyAdvisor {

  private static final MathContext STATISTICS_CONTEXT = new MathContext(40, RoundingMode.HALF_EVEN);

  private final AnomalyContextLoader contextLoader;
  private final AnomalyClient anomalyClient;
  private final JsonMapper responseMapper;

  public ExpenseAnomalyAdvisor(AnomalyContextLoader contextLoader, AnomalyClient anomalyClient) {
    this.contextLoader = contextLoader;
    this.anomalyClient = anomalyClient;
    responseMapper =
        JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
  }

  /** Any unavailable or untrusted advisory response deliberately becomes no advice. */
  public Optional<AnomalyResponse> assess(Expense expense, Instant postedAt) {
    try {
      AnomalyContext context = contextLoader.load(expense, postedAt);
      AnomalyResponse response =
          responseMapper.readValue(
              anomalyClient.assess(
                  expense.getId(),
                  expense.getCategoryId(),
                  expense.getCurrency(),
                  expense.getAmountMinor(),
                  context.history(),
                  context.budget()),
              AnomalyResponse.class);
      return isTrusted(response, expense, context) ? Optional.of(response) : Optional.empty();
    } catch (RuntimeException | JsonProcessingException exception) {
      return Optional.empty();
    }
  }

  private boolean isTrusted(AnomalyResponse response, Expense expense, AnomalyContext context) {
    if (response.expenseId() == null
        || !expense.getId().equals(response.expenseId())
        || response.risk() == null
        || response.historyCount() != context.history().size()
        || !Double.isFinite(response.zScore())
        || Double.compare(response.zScore(), expectedZScore(expense.getAmountMinor(), context.history())) != 0
        || response.explanation() == null
        || response.explanation().isBlank()
        || response.model() == null
        || response.model().isBlank()) {
      return false;
    }
    if (context.budget() == null) {
      return response.budgetBurnRate() == null;
    }
    return response.budgetBurnRate() != null
        && Double.isFinite(response.budgetBurnRate())
        && Double.compare(
                response.budgetBurnRate(),
                BigDecimal.valueOf(context.budget().spentMinor())
                    .divide(BigDecimal.valueOf(context.budget().limitMinor()), STATISTICS_CONTEXT)
                    .doubleValue())
            == 0;
  }

  private double expectedZScore(long amountMinor, java.util.List<AnomalyHistoryEntry> history) {
    if (history.size() < 2) {
      return 0.0;
    }
    java.util.List<BigDecimal> values =
        history.stream().map(entry -> BigDecimal.valueOf(entry.amountMinor())).toList();
    BigDecimal count = BigDecimal.valueOf(values.size());
    BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(count, STATISTICS_CONTEXT);
    BigDecimal variance =
        values.stream()
            .map(value -> value.subtract(mean).pow(2))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(count, STATISTICS_CONTEXT);
    if (variance.signum() == 0) {
      return 0.0;
    }
    return BigDecimal.valueOf(amountMinor)
        .subtract(mean)
        .divide(variance.sqrt(STATISTICS_CONTEXT), STATISTICS_CONTEXT)
        .doubleValue();
  }
}
