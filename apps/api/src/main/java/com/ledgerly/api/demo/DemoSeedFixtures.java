package com.ledgerly.api.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads the T5.3/T5.4 recorded fixtures — {@code db/seed/policy-chunks.json} and
 * {@code db/seed/invoice-extractions.json} — off the classpath. No network call, no LLM: these
 * were recorded once, offline, against a real embedding/extraction/categorization run (see
 * docs/decisions.md, 2026-08-25 entries).
 */
@Component
public class DemoSeedFixtures {

  private final ObjectMapper objectMapper;

  public DemoSeedFixtures(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public record PolicyChunkFixture(int chunkIndex, String chunkText, List<Double> embedding) {}

  public record PolicyFixture(String filename, List<PolicyChunkFixture> chunks) {}

  public record CategorizationFixture(String category, double confidence, String citation) {}

  public record InvoiceFixture(
      String sourcePdf, JsonNode extraction, CategorizationFixture categorization) {}

  public List<PolicyFixture> readPolicies() throws IOException {
    JsonNode root = read("/db/seed/policy-chunks.json");
    List<PolicyFixture> policies = new ArrayList<>();
    for (JsonNode policyNode : root.get("policies")) {
      List<PolicyChunkFixture> chunks = new ArrayList<>();
      for (JsonNode chunkNode : policyNode.get("chunks")) {
        List<Double> embedding = new ArrayList<>();
        for (JsonNode value : chunkNode.get("embedding")) {
          embedding.add(value.asDouble());
        }
        chunks.add(
            new PolicyChunkFixture(
                chunkNode.get("chunkIndex").asInt(), chunkNode.get("chunkText").asText(), embedding));
      }
      policies.add(new PolicyFixture(policyNode.get("filename").asText(), chunks));
    }
    return policies;
  }

  public List<InvoiceFixture> readInvoices() throws IOException {
    JsonNode root = read("/db/seed/invoice-extractions.json");
    List<InvoiceFixture> invoices = new ArrayList<>();
    for (JsonNode invoiceNode : root.get("invoices")) {
      JsonNode categorizationNode = invoiceNode.get("categorization");
      JsonNode citationNode = categorizationNode.get("citation");
      invoices.add(
          new InvoiceFixture(
              invoiceNode.get("sourcePdf").asText(),
              invoiceNode.get("extraction"),
              new CategorizationFixture(
                  categorizationNode.get("category").asText(),
                  categorizationNode.get("confidence").asDouble(),
                  citationNode == null || citationNode.isNull() ? null : citationNode.asText())));
    }
    return invoices;
  }

  private JsonNode read(String classpathResource) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(classpathResource)) {
      if (stream == null) {
        throw new IOException("Missing demo seed fixture: " + classpathResource);
      }
      return objectMapper.readTree(stream);
    }
  }
}
