package com.ledgerly.api.auth;

public class InvalidRefreshTokenException extends RuntimeException {

  public InvalidRefreshTokenException() {
    super("Invalid or expired refresh token");
  }
}
