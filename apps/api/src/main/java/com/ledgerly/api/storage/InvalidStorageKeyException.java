package com.ledgerly.api.storage;

/**
 * The supplied key is not one this store could have minted. Thrown before any filesystem call, so
 * a traversal attempt ({@code ../}, an absolute path) never reaches the disk at all.
 */
public class InvalidStorageKeyException extends RuntimeException {

  public InvalidStorageKeyException(String message) {
    super(message);
  }
}
