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
@Table(name = "organization")
public class Organization {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "base_currency", nullable = false)
  private String baseCurrency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Organization() {}

  public Organization(String name, String baseCurrency) {
    this.name = name;
    this.baseCurrency = baseCurrency;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getBaseCurrency() {
    return baseCurrency;
  }
}
