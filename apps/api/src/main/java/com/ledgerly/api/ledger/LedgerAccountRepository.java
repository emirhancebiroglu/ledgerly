package com.ledgerly.api.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
   * <p>Races the unique {@code (organization_id, name)} constraint on concurrent first-use: the
   * loser's insert fails with {@link DuplicateKeyException}, caught here and turned into a
   * lookup of the row the winner just committed, rather than a 500.
   */
  public UUID findOrCreate(UUID organizationId, String name, String accountType, String currency) {
    return findIdByOrganizationAndName(organizationId, name)
        .orElseGet(() -> insertOrRaceLookup(organizationId, name, accountType, currency));
  }

  private UUID insertOrRaceLookup(
      UUID organizationId, String name, String accountType, String currency) {
    UUID id = UUID.randomUUID();
    try {
      jdbcTemplate.update(
          "INSERT INTO account (id, organization_id, name, account_type, currency) "
              + "VALUES (?, ?, ?, ?, ?)",
          id,
          organizationId,
          name,
          accountType,
          currency);
      return id;
    } catch (DuplicateKeyException e) {
      return findIdByOrganizationAndName(organizationId, name)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Account insert raced but no row is visible for " + name, e));
    }
  }
}
