package com.ledgerly.api.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Runtime owner of the extraction-proposal contract shared with {@code ai}.
 *
 * <p>The schema is embedded from {@code docs/contracts/} when the artifact is built. Loading it in
 * the component constructor makes a missing or malformed contract a startup failure, rather than
 * letting the API accept unvalidated agent output.
 */
@Component
public class ExtractionProposalContract {

  static final String RESOURCE_PATH = "contracts/extraction-proposal.schema.json";

  private static final JsonSchemaFactory SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig VALIDATION_CONFIG =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  private final JsonMapper jsonMapper;
  private final JsonSchema schema;

  public ExtractionProposalContract() {
    this(new ClassPathResource(RESOURCE_PATH));
  }

  ExtractionProposalContract(Resource schemaResource) {
    this.jsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    this.schema = load(schemaResource);
  }

  /** Rejects JSON that does not conform to the shared schema without exposing document content. */
  public void validate(String rawProposal) {
    JsonNode proposal = parse(rawProposal);
    Set<com.networknt.schema.ValidationMessage> violations = schema.validate(proposal);
    if (!violations.isEmpty()) {
      throw new MalformedProposalException("Proposal did not match the agreed contract");
    }
  }

  private JsonNode parse(String rawProposal) {
    if (rawProposal == null) {
      throw new MalformedProposalException("Proposal did not match the agreed contract");
    }
    try {
      JsonNode proposal = jsonMapper.readTree(rawProposal);
      if (proposal == null) {
        throw new MalformedProposalException("Proposal did not match the agreed contract");
      }
      return proposal;
    } catch (JsonProcessingException e) {
      throw new MalformedProposalException("Proposal did not match the agreed contract", e);
    }
  }

  private static JsonSchema load(Resource schemaResource) {
    try (InputStream input = schemaResource.getInputStream()) {
      return SCHEMA_FACTORY.getSchema(
          new String(input.readAllBytes(), StandardCharsets.UTF_8), VALIDATION_CONFIG);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Could not load the extraction proposal contract", e);
    }
  }
}
