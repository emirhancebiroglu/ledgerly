package com.ledgerly.api.policy;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the `ai` service's {@code POST /embed-policy} over HTTP. Mirrors HttpExtractionClient. */
@Component
public class HttpPolicyEmbeddingClient implements PolicyEmbeddingClient {

  private final RestClient restClient;

  public HttpPolicyEmbeddingClient(
      RestClient.Builder builder,
      @Value("${ledgerly.ai.base-url}") String baseUrl,
      @Value("${ledgerly.ai.timeout-seconds:30}") long timeoutSeconds) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  @Override
  public String embedPolicy(UUID policyDocumentId, byte[] content, String contentType) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return "policy.pdf";
          }
        });
    parts.add("policy_document_id", policyDocumentId.toString());
    parts.add("content_type", contentType);
    String correlationId = CorrelationIdHolder.current();
    if (correlationId != null) {
      parts.add("correlation_id", correlationId);
    }

    try {
      return restClient
          .post()
          .uri("/embed-policy")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(parts)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw new PolicyEmbeddingUnavailableException("Policy embedding service call failed", e);
    }
  }
}
