package com.ledgerly.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class StorageKeysTest {

  @Test
  void acceptsACanonicalLowerCaseUuid() {
    assertThat(StorageKeys.isValidShape("f47ac10b-58cc-4372-a567-0e02b2c3d479")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../etc/passwd", "not-a-uuid", "", "abc/def", "F47AC10B-58CC-4372-A567-0E02B2C3D479"})
  void rejectsAnythingNotACanonicalLowerCaseUuid(String key) {
    assertThat(StorageKeys.isValidShape(key)).isFalse();
  }

  @Test
  void rejectsNull() {
    assertThat(StorageKeys.isValidShape(null)).isFalse();
  }
}
