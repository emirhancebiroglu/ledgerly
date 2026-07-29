package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class ExtractionProposalContractTest {

  @Test
  void embedsTheCanonicalSchemaByteForByte() throws Exception {
    byte[] canonical =
        Files.readAllBytes(ContractSchemas.contractsDirectory().resolve("extraction-proposal.schema.json"));
    byte[] embedded;
    try (InputStream input =
        new ClassPathResource(ExtractionProposalContract.RESOURCE_PATH).getInputStream()) {
      embedded = input.readAllBytes();
    }

    assertThat(embedded).isEqualTo(canonical);
    assertThat(new ExtractionProposalContract()).isNotNull();
  }

  @Test
  void failsClosedWhenTheSchemaResourceIsMissingUnreadableOrMalformed() {
    assertThatThrownBy(
            () ->
                new ExtractionProposalContract(
                    new ClassPathResource("contracts/does-not-exist.schema.json")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ExtractionProposalContract(new UnreadableResource()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new ExtractionProposalContract(
                    new ByteArrayResource("{not json".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static final class UnreadableResource extends AbstractResource {

    @Override
    public String getDescription() {
      return "unreadable test resource";
    }

    @Override
    public InputStream getInputStream() throws IOException {
      throw new IOException("simulated unreadable schema");
    }
  }
}
