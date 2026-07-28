package com.ledgerly.api.ai;

import com.ledgerly.api.correlation.CorrelationIdHolder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/** Creates authenticated, timeout-bounded clients for the private {@code ai} service. */
@Component
public class AiRestClientFactory {

  private final RestClient.Builder builder;
  private final String serviceToken;

  public AiRestClientFactory(
      RestClient.Builder builder, @Value("${ledgerly.ai.service-token}") String serviceToken) {
    Assert.hasText(serviceToken, "ledgerly.ai.service-token must not be blank");
    this.builder = builder;
    this.serviceToken = serviceToken;
  }

  public RestClient create(String baseUrl, long timeoutSeconds) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    return builder
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
        .requestInterceptor(
            (request, body, execution) -> {
              String correlationId = CorrelationIdHolder.current();
              if (correlationId != null) {
                request.getHeaders().set("X-Correlation-Id", correlationId);
              }
              return execution.execute(request, body);
            })
        .build();
  }
}
