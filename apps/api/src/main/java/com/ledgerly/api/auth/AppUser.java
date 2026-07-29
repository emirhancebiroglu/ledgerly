package com.ledgerly.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String email;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(UUID organizationId, String fullName, String email, String passwordHash) {
    this.organizationId = organizationId;
    this.fullName = fullName;
    this.email = email;
    this.passwordHash = passwordHash;
    this.createdAt = Instant.now();
  }

  /** Preserves pre-M9 fixtures and programmatic callers while existing rows use the same fallback. */
  public AppUser(UUID organizationId, String email, String passwordHash) {
    this(organizationId, "Ledgerly user", email, passwordHash);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getEmail() {
    return email;
  }

  public String getFullName() {
    return fullName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }
}
