package com.ledgerly.api.alert;

/** {@code GET /api/v1/alerts} was called with an unknown {@code type} filter value. */
public class InvalidAlertTypeException extends RuntimeException {

  public InvalidAlertTypeException(String message) {
    super(message);
  }
}
