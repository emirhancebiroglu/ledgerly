package com.ledgerly.api.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for {@code account} — like {@link
 * com.ledgerly.api.policy.PolicyChunkRepository}, this table has no JPA entity because nothing in
 * this codebase yet needed one beyond lookup-by-name and find-or-create.
 */
@Repository
public class LedgerAccountRepository {

  private final JdbcTemplate jdbcTemplate;

  public LedgerAccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<UUID> findIdByOrganizationAndName(UUID organizationId, String name) {
    return jdbcTemplate
        .query(
            "SELECT id FROM account WHERE organization_id = ? AND name = ?",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            organizationId,
            name)
        .stream()
        .findFirst();
  }

  /**
   * Finds the named account for the organization, creating it as {@code accountType} if it does
   * not already exist. A category classified for the first time in an organization needs a real
   * general-ledger account to post against — proper double-entry bookkeeping requires the
   * category to be a first-class ledger dimension, not sidecar metadata on the expense row.
   *
   * <p>Races the unique {@code (organization_id, name)} constraint on concurrent first-use via
   * {@code ON CONFLICT ... DO NOTHING} rather than catching {@link DuplicateKeyException}: in
   * Postgres, a failed statement aborts the entire enclosing transaction (this runs inside the
   * caller's {@code @Transactional}), so every statement after a caught constraint violation —
   * including the recovery lookup — would itself fail with "current transaction is aborted."
   * {@code ON CONFLICT} never raises, so the transaction stays usable and the lookup after it is
   * unconditional.
   */
  public UUID findOrCreate(UUID organizationId, String name, String accountType, String currency) {
    return findIdByOrganizationAndName(organizationId, name)
        .orElseGet(() -> insertIgnoringConflictThenLookup(organizationId, name, accountType, currency));
  }

  private UUID insertIgnoringConflictThenLookup(
      UUID organizationId, String name, String accountType, String currency) {
    jdbcTemplate.update(
        "INSERT INTO account (id, organization_id, name, account_type, currency) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (organization_id, name) DO NOTHING",
        UUID.randomUUID(),
        organizationId,
        name,
        accountType,
        currency);
    return findIdByOrganizationAndName(organizationId, name)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Account insert-or-lookup found no row for " + name));
  }
}
