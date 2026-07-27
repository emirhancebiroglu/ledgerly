package com.ledgerly.api.document;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates and loads the shared schemas in {@code docs/contracts/}. `api` reads the contract from
 * that directory rather than restating it in Java, so the two services cannot drift apart without a
 * test failing.
 */
final class ContractSchemas {

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private ContractSchemas() {}

  /**
   * Walks up from the module directory to the repository root. Tests run with the working directory
   * at {@code apps/api}, but that is a convention, not a guarantee — searching for the marker
   * directory means the test does not silently pass by finding nothing.
   */
  static Path contractsDirectory() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve("docs").resolve("contracts");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate docs/contracts from " + Path.of("").toAbsolutePath());
  }

  static Path example(String name) {
    return contractsDirectory().resolve("examples").resolve(name);
  }

  static JsonSchema load(String schemaFileName) {
    Path schemaPath = contractsDirectory().resolve(schemaFileName);
    if (!Files.isRegularFile(schemaPath)) {
      throw new IllegalStateException("Missing contract schema: " + schemaPath);
    }
    try {
      return FACTORY.getSchema(Files.readString(schemaPath));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to read contract schema " + schemaPath, e);
    }
  }
}
