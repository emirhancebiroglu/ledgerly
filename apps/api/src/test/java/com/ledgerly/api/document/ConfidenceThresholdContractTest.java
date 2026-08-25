package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/contracts/thresholds.json} is the single source of truth for the confidence
 * threshold that gates both {@code api}'s categorization posting decision and {@code ai}'s
 * extraction self-check retry. This test reads {@code application.yml}'s configured default
 * directly rather than trusting a comment to keep the two in sync — a value edited on one side
 * without the other fails here instead of silently diverging in production.
 */
class ConfidenceThresholdContractTest {

  // Matches `confidence-threshold: ${CATEGORIZATION_CONFIDENCE_THRESHOLD:0.7}` and captures the
  // default after the colon inside the placeholder -- the value Spring actually binds when the
  // environment variable is unset, which is every environment this project runs in today.
  private static final Pattern CONFIGURED_DEFAULT =
      Pattern.compile("confidence-threshold:\\s*\\$\\{[A-Z_]+:([0-9.]+)}");

  @Test
  void applicationYmlsConfiguredDefaultMatchesTheSharedContract() throws IOException {
    double contractValue = readContractThreshold();
    double configuredDefault = readApplicationYmlDefault();

    assertThat(configuredDefault)
        .as(
            "application.yml's ledgerly.categorization.confidence-threshold default has drifted "
                + "from docs/contracts/thresholds.json's confidenceThreshold -- ai's "
                + "extraction_graph.CONFIDENCE_THRESHOLD must change together with this value")
        .isEqualTo(contractValue);
  }

  private double readContractThreshold() throws IOException {
    Path contractPath = ContractSchemas.contractsDirectory().resolve("thresholds.json");
    Map<String, Object> contract =
        new JsonMapper().readValue(Files.readString(contractPath), new TypeReference<Map<String, Object>>() {});
    Object value = contract.get("confidenceThreshold");
    assertThat(value).as("thresholds.json must define confidenceThreshold").isNotNull();
    return ((Number) value).doubleValue();
  }

  private double readApplicationYmlDefault() throws IOException {
    Path applicationYml = Path.of("src", "main", "resources", "application.yml").toAbsolutePath();
    assertThat(Files.isRegularFile(applicationYml))
        .as("application.yml not found at " + applicationYml)
        .isTrue();
    String content = Files.readString(applicationYml);
    Matcher matcher = CONFIGURED_DEFAULT.matcher(content);
    assertThat(matcher.find())
        .as("could not find a confidence-threshold: ${...:<default>} line in application.yml")
        .isTrue();
    return Double.parseDouble(matcher.group(1));
  }
}
