package com.ledgerly.api.document;

import com.ledgerly.api.ai.AiRestClientFactory;
import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the `ai` service over HTTP. */
@Component
public class HttpExtractionClient implements ExtractionClient {

  private final RestClient restClient;

  public HttpExtractionClient(
      AiRestClientFactory clientFactory,
      @Value("${ledgerly.ai.base-url}") String baseUrl,
      @Value("${ledgerly.ai.timeout-seconds:30}") long timeoutSeconds) {
    this.restClient = clientFactory.create(baseUrl, timeoutSeconds);
  }

  @Override
  public String extract(UUID documentId, byte[] content, String contentType, String filename) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    parts.add("document_id", documentId.toString());
    // The type `api` established from the bytes, not the one the uploader claimed.
    parts.add("content_type", contentType);
    String correlationId = CorrelationIdHolder.current();
    if (correlationId != null) {
      parts.add("correlation_id", correlationId);
    }

    try {
      return restClient
          .post()
          .uri("/extract")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(parts)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      // Covers timeouts, connection failures and any non-2xx: from this side they are the same
      // event — no usable proposal came back.
      throw new ExtractionUnavailableException("Extraction service call failed", e);
    }
  }
}
