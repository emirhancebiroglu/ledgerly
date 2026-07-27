package com.ledgerly.api.expense;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the `ai` service's {@code POST /categorize} over HTTP. Mirrors HttpExtractionClient. */
@Component
public class HttpCategorizationClient implements CategorizationClient {

  private final RestClient restClient;

  public HttpCategorizationClient(
      RestClient.Builder builder,
      @Value("${ledgerly.ai.base-url}") String baseUrl,
      @Value("${ledgerly.ai.timeout-seconds:30}") long timeoutSeconds) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  @Override
  public String categorize(
      UUID documentId,
      String vendor,
      String currency,
      long totalMinor,
      String documentDate,
      List<String> categories,
      List<String> policyChunkTexts) {
    Map<String, Object> body =
        new java.util.LinkedHashMap<>(
            Map.of(
                "document_id", documentId.toString(),
                "currency", currency,
                "total_minor", totalMinor,
                "categories", categories,
                "policy_chunks",
                    policyChunkTexts.stream().map(text -> Map.of("chunk_text", text)).toList()));
    if (vendor != null) {
      body.put("vendor", vendor);
    }
    if (documentDate != null) {
      body.put("document_date", documentDate);
    }
    String correlationId = CorrelationIdHolder.current();
    if (correlationId != null) {
      body.put("correlation_id", correlationId);
    }

    try {
      return restClient
          .post()
          .uri("/categorize")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw new CategorizationUnavailableException("Categorization service call failed", e);
    }
  }
}
