package com.ledgerly.api.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Minimal mutating write to exercise the idempotency filter (M3 T4); replaced by the real
 * expense/ledger-transaction pipeline at M4/M6. */
@Entity
@Table(name = "expense_stub")
public class ExpenseStub {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(nullable = false, columnDefinition = "bpchar(3)")
  private String currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ExpenseStub() {}

  public ExpenseStub(UUID organizationId, long amountMinor, String currency) {
    this.organizationId = organizationId;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
  }
}
