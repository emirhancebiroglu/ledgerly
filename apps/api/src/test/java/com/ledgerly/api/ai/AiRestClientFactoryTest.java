package com.ledgerly.api.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AiRestClientFactoryTest {

  @Test
  void every_client_created_for_ai_sends_the_service_bearer_credential() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    AiRestClientFactory factory = new AiRestClientFactory(builder, "test-service-token");
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/extract",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();

    try {
      factory
          .create("http://127.0.0.1:" + server.getAddress().getPort(), 2)
          .post()
          .uri("/extract")
          .retrieve()
          .toBodilessEntity();
    } finally {
      server.stop(0);
    }

    assertThat(authorization.get()).isEqualTo("Bearer test-service-token");
  }
}
