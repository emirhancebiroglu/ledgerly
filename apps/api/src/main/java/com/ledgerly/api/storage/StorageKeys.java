package com.ledgerly.api.storage;

import java.util.regex.Pattern;

/**
 * The one key shape every {@link StorageClient} implementation mints and accepts — a bare,
 * canonical lower-case UUID. Shared so the pattern can only diverge once, not once per backend.
 */
final class StorageKeys {

  private static final Pattern KEY_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private StorageKeys() {}

  static boolean isValidShape(String key) {
    return key != null && KEY_PATTERN.matcher(key).matches();
  }
}
