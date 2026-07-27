package com.ledgerly.api.storage;

/** No content exists under the requested storage key. */
public class StorageKeyNotFoundException extends RuntimeException {

  public StorageKeyNotFoundException(String message) {
    super(message);
  }
}
