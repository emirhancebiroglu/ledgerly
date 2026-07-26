package com.ledgerly.api.auth;

public class CrossOrganizationAccessException extends RuntimeException {

  public CrossOrganizationAccessException() {
    super("Resource does not belong to the caller's organization");
  }
}
