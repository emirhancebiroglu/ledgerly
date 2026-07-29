package com.ledgerly.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(max = 100) String fullName,
    @NotBlank @Size(max = 100) String company,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 12, max = 128) String password) {

  /** Temporary source compatibility for existing API integration fixtures. JSON registration still
   * requires a caller-supplied full name through the canonical constructor. */
  public RegisterRequest(String company, String email, String password) {
    this("Ledgerly user", company, email, password);
  }
}
