package com.ledgerly.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalDiskStorageTest {

  @TempDir Path tempDir;

  private Path root;
  private LocalDiskStorage storage;

  @BeforeEach
  void setUp() {
    root = tempDir.resolve("blobs");
    storage = new LocalDiskStorage(root);
  }

  @Test
  void roundTripsBytesUnderAKeyTheCallerNeverChose() {
    byte[] content = "invoice bytes".getBytes(StandardCharsets.UTF_8);

    String key = storage.store(content);

    assertThat(key).isNotBlank();
    assertThat(storage.read(key)).isEqualTo(content);
  }

  @Test
  void twoStoresOfIdenticalContentGetDistinctKeys() {
    byte[] content = "identical".getBytes(StandardCharsets.UTF_8);

    String first = storage.store(content);
    String second = storage.store(content);

    assertThat(first).isNotEqualTo(second);
    assertThat(storage.read(first)).isEqualTo(content);
    assertThat(storage.read(second)).isEqualTo(content);
  }

  @Test
  void mintedKeyLeaksNoFilesystemPath() {
    String key = storage.store("bytes".getBytes(StandardCharsets.UTF_8));

    assertThat(key).doesNotContain("/", "\\", "..", root.toString());
    assertThat(UUID.fromString(key)).hasToString(key);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../etc/passwd",
        "../../../../etc/passwd",
        "..",
        "a/../../b",
        "/etc/passwd",
        "C:\\Windows\\System32\\config\\SAM",
        "\\\\server\\share\\file",
        "subdir/00000000-0000-0000-0000-000000000000",
        "00000000-0000-0000-0000-000000000000/../../escape",
        "",
        "not-a-uuid"
      })
  void rejectsTraversalAndAbsolutePathKeysBeforeAnyFilesystemCall(String maliciousKey) {
    assertThatThrownBy(() -> storage.read(maliciousKey))
        .isInstanceOf(InvalidStorageKeyException.class);
  }

  @Test
  void rejectsAKeyContainingANullByte() {
    String key = storage.store("bytes".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> storage.read(key + "\u0000.png"))
        .isInstanceOf(InvalidStorageKeyException.class);
  }

  @Test
  void traversalAttemptNeverReadsAFileOutsideTheRoot() throws IOException {
    Path secret = tempDir.resolve("secret.txt");
    Files.writeString(secret, "top secret");
    String relativeToSecret = "../secret.txt";

    assertThatThrownBy(() -> storage.read(relativeToSecret))
        .isInstanceOf(InvalidStorageKeyException.class);
    // The file is still there and was never served through the store.
    assertThat(Files.readString(secret)).isEqualTo("top secret");
  }

  @Test
  void readingAnUnknownKeyRaisesNotFoundRatherThanReturningEmpty() {
    String unknownButWellFormed = UUID.randomUUID().toString();

    assertThatThrownBy(() -> storage.read(unknownButWellFormed))
        .isInstanceOf(StorageKeyNotFoundException.class);
  }

  @Test
  void createsTheStorageRootOnConstruction() {
    assertThat(Files.isDirectory(root)).isTrue();
  }

  @Test
  void storesOutsideAnyWebServedDirectory() throws IOException {
    storage.store("bytes".getBytes(StandardCharsets.UTF_8));

    // Nothing lands in a directory Spring Boot would serve statically.
    try (Stream<Path> tree = Files.walk(root)) {
      assertThat(tree.map(path -> root.relativize(path).toString()))
          .noneMatch(
              relative ->
                  relative.startsWith("static")
                      || relative.startsWith("public")
                      || relative.startsWith("resources")
                      || relative.startsWith("META-INF"));
    }
  }

  @Test
  void storedBlobIsNotExecutableOrGuessableFromContent() {
    byte[] content = "predictable content".getBytes(StandardCharsets.UTF_8);

    String key = storage.store(content);

    // The key is random, not a hash of the content: an attacker who knows the exact bytes still
    // cannot derive the key.
    assertThat(key).isNotEqualTo(UUID.nameUUIDFromBytes(content).toString());
  }
}
