package com.ledgerly.api.anomaly;

import com.ledgerly.api.ai.AiRestClientFactory;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter; the advisor treats all transport and remote-validation failures as unavailable. */
@Component
public class HttpAnomalyClient implements AnomalyClient {

  private final RestClient restClient;

  public HttpAnomalyClient(
      AiRestClientFactory clientFactory,
      @Value("${ledgerly.ai.base-url}") String baseUrl,
      @Value("${ledgerly.ai.timeout-seconds:30}") long timeoutSeconds) {
    restClient = clientFactory.create(baseUrl, timeoutSeconds);
  }

  @Override
  public String assess(
      UUID expenseId,
      UUID categoryId,
      String currency,
      long amountMinor,
      List<AnomalyHistoryEntry> history,
      AnomalyBudgetSnapshot budget) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expense_id", expenseId.toString());
    body.put("category_id", categoryId.toString());
    body.put("currency", currency);
    body.put("amount_minor", amountMinor);
    body.put(
        "history",
        history.stream()
            .map(entry -> Map.of("amount_minor", entry.amountMinor(), "posted_at", entry.postedAt().toString()))
            .toList());
    body.put(
        "budget",
        budget == null
            ? null
            : Map.of(
                "period", budget.period(),
                "limit_minor", budget.limitMinor(),
                "spent_minor", budget.spentMinor()));
    String correlationId = CorrelationIdHolder.current();
    if (correlationId != null) {
      body.put("correlation_id", correlationId);
    }
    try {
      return restClient
          .post()
          .uri("/anomaly")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
    } catch (RestClientException exception) {
      throw new AnomalyUnavailableException("Anomaly service call failed", exception);
    }
  }
}
