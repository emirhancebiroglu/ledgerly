package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.auth.AuthResponse;
import com.ledgerly.api.auth.RegisterRequest;
import com.ledgerly.api.ledger.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M9.9 T6's demo command: {@code api} boots and serves the full loop with no Redis process
 * reachable at all.
 *
 * <p>Deliberately does not extend {@link AbstractPostgresIT}: that base class's own {@code
 * @DynamicPropertySource} unconditionally points {@code spring.data.redis.host/port} at a real
 * Testcontainers Redis it starts itself, alongside Postgres. A subclass adding a second {@code
 * @DynamicPropertySource} does not reliably win that race -- confirmed the hard way: an earlier
 * version of this test extended it and a real Redis container came up anyway, silently defeating
 * the point of the test. Sharing only {@link AbstractPostgresIT#POSTGRES} directly keeps Redis
 * out of the wiring entirely rather than trying to override it after the fact.
 *
 * <p>Referencing {@code POSTGRES} still triggers {@code AbstractPostgresIT}'s static initializer
 * (a non-constant {@code static final} field forces class init per JLS 12.4.1), which starts that
 * class's own Redis Testcontainer too -- wastefully, since this class's own {@code
 * @DynamicPropertySource} below never points at it. Harmless: nothing here auto-discovers Redis
 * off the Docker network, only off the explicit {@code spring.data.redis.host}/{@code port}
 * properties, which are set to an unreachable address regardless. Not worth splitting {@code
 * POSTGRES} into its own holder class for one avoided idle container.
 *
 * <p>{@code ai} is stubbed via {@link ExtractionClient}, the same pattern {@code
 * DocumentStatusPipelineIT} uses -- this test is about the Redis dependency, not about a live
 * {@code ai} call, and a real one would make the demo command depend on a service this class has
 * no way to guarantee is running.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import({NoRedisBootIT.StubExtractionConfig.class, NoRedisBootIT.SynchronousAsyncConfig.class})
@TestPropertySource(
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "ledgerly.rate-limit.backend=in-memory",
      "ledgerly.document.event-broker=in-memory"
    })
class NoRedisBootIT {

  @DynamicPropertySource
  static void datasourceAndNoRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", AbstractPostgresIT.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", AbstractPostgresIT.POSTGRES::getUsername);
    registry.add("spring.datasource.password", AbstractPostgresIT.POSTGRES::getPassword);
    // No Redis container started for this context at all -- host/port point at an address
    // nothing listens on, so any code path that still tried to reach Redis would fail loudly
    // rather than quietly finding a real instance nearby.
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", () -> 1);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentQueuePoller queuePoller;

  @Test
  void uploadExtractionAndSseAllWorkWithNoRedisReachable() throws Exception {
    String token = registerAndGetAccessToken();

    MvcResult upload =
        mockMvc
            .perform(
                multipart("/api/v1/documents")
                    .file(
                        new MockMultipartFile(
                            "file", "invoice.pdf", "application/pdf", TestPdfFactory.validPdf()))
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();

    UUID documentId =
        UUID.fromString(
            objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText());

    MvcResult subscribe =
        mockMvc
            .perform(
                get("/api/v1/documents/" + documentId + "/events")
                    .header("Authorization", "Bearer " + token))
            .andExpect(request -> assertThat(request.getRequest().isAsyncStarted()).isTrue())
            .andReturn();

    queuePoller.processDueDocuments();

    subscribe.getAsyncResult(10_000);
    assertThat(subscribe.getResponse().getContentAsString()).contains("EXTRACTING");
  }

  private String registerAndGetAccessToken() throws Exception {
    String email = "no-redis-boot-" + System.nanoTime() + "@example.com";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(
                                "org-" + System.nanoTime(), email, "correct-horse-battery"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private static String validProposal(UUID documentId) {
    return """
        {"document_id":"%s","vendor":"Contoso","currency":"EUR","total_minor":12100,
         "tax_minor":2100,"document_date":"%s",
         "lines":[{"description":"item a","quantity":1000,"amount_minor":4000},
                  {"description":"item b","quantity":1000,"amount_minor":6000}],
         "confidence":{"vendor":0.9,"currency":0.99,"total_minor":0.95,"tax_minor":0.9,
                       "document_date":0.93},
         "model":"fake-llm-v1","warnings":[]}
        """
        .formatted(documentId, LocalDate.now().minusDays(3));
  }

  @TestConfiguration
  static class StubExtractionConfig {
    @Bean
    @Primary
    ExtractionClient stubExtractionClient() {
      return (documentId, content, contentType, filename) -> validProposal(documentId);
    }
  }

  /** Runs {@code @Async} work on the calling thread after the test explicitly polls the queue. */
  @TestConfiguration
  static class SynchronousAsyncConfig implements AsyncConfigurer {
    @Bean(com.ledgerly.api.AsyncConfig.DOCUMENT_PROCESSING_EXECUTOR)
    @Primary
    java.util.concurrent.Executor documentProcessingExecutor() {
      return new org.springframework.core.task.SyncTaskExecutor();
    }

    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
      return documentProcessingExecutor();
    }
  }
}
