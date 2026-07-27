package com.ledgerly.api.storage;

/** The store could not complete an otherwise valid operation (I/O failure). */
public class StorageException extends RuntimeException {

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
