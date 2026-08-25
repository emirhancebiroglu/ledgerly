package com.ledgerly.api.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * T5.3: this fixture was recorded once, offline, by uploading the T5.1 policy PDFs through the
 * real {@code PolicyUploadService} against a real embedding provider, then exporting the
 * resulting {@code policy_chunk} rows. T5.5's demo seed replays it — no LLM call at seed time.
 */
class DemoSeedPolicyChunksFixtureTest {

  private static final String FIXTURE_PATH = "/db/seed/policy-chunks.json";

  @Test
  void fixtureExistsAndIsValidJson() throws IOException {
    JsonNode root = readFixture();
    assertThat(root.isObject()).isTrue();
  }

  @Test
  void fixtureHasOnePolicyEntryPerGeneratedPolicyPdf() throws IOException {
    JsonNode root = readFixture();
    JsonNode policies = root.get("policies");

    assertThat(policies.isArray()).isTrue();
    assertThat(policies.size()).isEqualTo(3);

    var filenames =
        java.util.stream.StreamSupport.stream(policies.spliterator(), false)
            .map(p -> p.get("filename").asText())
            .toList();
    assertThat(filenames)
        .containsExactlyInAnyOrder(
            "travel_and_expense_policy.pdf",
            "software_and_subscriptions_policy.pdf",
            "client_entertainment_policy.pdf");
  }

  @Test
  void everyChunkHasNonEmptyTextAndAnEmbeddingVector() throws IOException {
    JsonNode root = readFixture();
    int declaredDimensions = root.get("embeddingDimensions").asInt();

    for (JsonNode policy : root.get("policies")) {
      for (JsonNode chunk : policy.get("chunks")) {
        assertThat(chunk.get("chunkText").asText()).isNotBlank();
        JsonNode embedding = chunk.get("embedding");
        assertThat(embedding.isArray()).isTrue();
        assertThat(embedding.size()).isEqualTo(declaredDimensions);
      }
    }
  }

  private JsonNode readFixture() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(FIXTURE_PATH)) {
      assertThat(stream).as(FIXTURE_PATH + " must be on the classpath").isNotNull();
      return new ObjectMapper().readTree(stream);
    }
  }
}
