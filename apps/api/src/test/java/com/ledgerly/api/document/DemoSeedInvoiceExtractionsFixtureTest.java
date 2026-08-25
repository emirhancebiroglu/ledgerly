package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * T5.4: recorded once, offline, by uploading T5.2's invoices through the real
 * {@code DocumentUploadService} pipeline against real extraction/categorization. T5.5's demo
 * seed replays it — no LLM call at seed time.
 */
class DemoSeedInvoiceExtractionsFixtureTest {

  private static final String FIXTURE_PATH = "/db/seed/invoice-extractions.json";

  @Test
  void fixtureExistsAndIsValidJson() throws IOException {
    assertThat(readFixture().isObject()).isTrue();
  }

  @Test
  void fixtureHasOneEntryPerInvoiceInTheGeneratorManifest() throws IOException {
    JsonNode fixture = readFixture();
    JsonNode manifest = readManifest();

    assertThat(fixture.get("invoices").size()).isEqualTo(manifest.get("entries").size());
  }

  @Test
  void everyFixtureEntryMatchesItsManifestEntryOnVendorAmountAndCurrency() throws IOException {
    JsonNode fixture = readFixture();
    JsonNode manifest = readManifest();

    var manifestByPath = new java.util.HashMap<String, JsonNode>();
    for (JsonNode entry : manifest.get("entries")) {
      manifestByPath.put(entry.get("path").asText(), entry);
    }

    for (JsonNode invoice : fixture.get("invoices")) {
      String sourcePdf = invoice.get("sourcePdf").asText();
      JsonNode manifestEntry = manifestByPath.get(sourcePdf);
      assertThat(manifestEntry).as(sourcePdf + " must exist in the manifest").isNotNull();

      JsonNode expected = manifestEntry.get("expected");
      JsonNode extraction = invoice.get("extraction");
      // vendor is allowed to be absent/null: the low_confidence scenario's whole point is a
      // real LLM being unable to read a vendor off a deliberately sparse invoice — checking it
      // strictly here would fail the one fixture entry that's supposed to look like that.
      JsonNode vendorNode = extraction.get("vendor");
      if (vendorNode != null && !vendorNode.isNull()) {
        // Case-insensitive: the real LLM reads vendor names off the PDF's own casing (e.g. an
        // all-caps letterhead), which need not match the manifest's title-case label — that's a
        // faithful extraction, not a mismatch.
        assertThat(vendorNode.asText()).isEqualToIgnoringCase(expected.get("vendor").asText());
      }
      assertThat(extraction.get("currency").asText()).isEqualTo(expected.get("currency").asText());
      assertThat(extraction.get("total_minor").asLong()).isEqualTo(expected.get("total_minor").asLong());
    }
  }

  @Test
  void everyFixtureEntryHasACategorizationResult() throws IOException {
    for (JsonNode invoice : readFixture().get("invoices")) {
      JsonNode categorization = invoice.get("categorization");
      assertThat(categorization.get("category").asText()).isNotBlank();
      assertThat(categorization.get("confidence").asDouble()).isBetween(0.0, 1.0);
    }
  }

  @Test
  void theDuplicateConfirmedEntryAndTheBudgetThresholdEntriesAreAllPresentAndPosted()
      throws IOException {
    JsonNode fixture = readFixture();

    long budgetThresholdCount =
        java.util.stream.StreamSupport.stream(fixture.get("invoices").spliterator(), false)
            .filter(inv -> inv.get("sourcePdf").asText().contains("boardroom_bistro"))
            .count();
    assertThat(budgetThresholdCount).isGreaterThanOrEqualTo(3);

    boolean hasDuplicate =
        java.util.stream.StreamSupport.stream(fixture.get("invoices").spliterator(), false)
            .anyMatch(inv -> inv.get("sourcePdf").asText().contains("quickprint_2026-03_duplicate"));
    assertThat(hasDuplicate).isTrue();

    boolean hasAnomalyOutlier =
        java.util.stream.StreamSupport.stream(fixture.get("invoices").spliterator(), false)
            .anyMatch(inv -> inv.get("sourcePdf").asText().contains("techgear_2026-06_outlier"));
    assertThat(hasAnomalyOutlier).isTrue();
  }

  private JsonNode readFixture() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(FIXTURE_PATH)) {
      assertThat(stream).as(FIXTURE_PATH + " must be on the classpath").isNotNull();
      return new ObjectMapper().readTree(stream);
    }
  }

  private JsonNode readManifest() throws IOException {
    // Not on the classpath — scripts/demo_seed/ is a build-time tool directory, not a Java
    // resource. Read it from the repo-relative path the way DatabaseUrlEnvironmentPostProcessorTest
    // reads its own resources — here, straight off disk relative to the module root.
    java.nio.file.Path manifestPath =
        java.nio.file.Path.of("..", "..", "scripts", "demo_seed", "invoice_manifest.json");
    assertThat(manifestPath).as(manifestPath + " must exist").exists();
    return new ObjectMapper().readTree(manifestPath.toFile());
  }
}
