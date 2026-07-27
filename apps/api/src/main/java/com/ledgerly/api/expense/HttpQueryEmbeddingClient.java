package com.ledgerly.api.expense;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the `ai` service's {@code POST /embed-query} over HTTP. */
@Component
public class HttpQueryEmbeddingClient implements QueryEmbeddingClient {

  private final RestClient restClient;

  public HttpQueryEmbeddingClient(
      RestClient.Builder builder,
      @Value("${ledgerly.ai.base-url}") String baseUrl,
      @Value("${ledgerly.ai.timeout-seconds:30}") long timeoutSeconds) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  @Override
  public String embedQuery(String text) {
    Map<String, Object> body = new LinkedHashMap<>(Map.of("text", text));
    String correlationId = CorrelationIdHolder.current();
    if (correlationId != null) {
      body.put("correlation_id", correlationId);
    }

    try {
      return restClient
          .post()
          .uri("/embed-query")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw new QueryEmbeddingUnavailableException("Embed-query service call failed", e);
    }
  }
}
