package com.ledgerly.api.ledger;

import java.sql.PreparedStatement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for {@link LedgerTransaction}/{@link LedgerEntry} — these are pure domain
 * objects with no JPA mapping (see their own Javadoc), consistent with how {@code
 * AbstractPostgresIT} has always written them in tests. This is the first production-code writer.
 */
@Repository
public class LedgerTransactionRepository {

  private final JdbcTemplate jdbcTemplate;

  public LedgerTransactionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Persists the transaction header and every entry. Caller is responsible for the enclosing transaction. */
  public void save(LedgerTransaction transaction) {
    jdbcTemplate.update(
        "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at, description) "
            + "VALUES (?, ?, ?, ?, ?)",
        transaction.id(),
        transaction.organizationId(),
        transaction.baseCurrency(),
        java.sql.Timestamp.from(transaction.postedAt()),
        (String) null);

    List<LedgerEntry> entries = transaction.entries();
    jdbcTemplate.batchUpdate(
        "INSERT INTO ledger_entry "
            + "(id, transaction_id, account_id, direction, native_amount_minor, native_currency, "
            + "base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        entries,
        entries.size(),
        (PreparedStatement ps, LedgerEntry entry) -> {
          ps.setObject(1, java.util.UUID.randomUUID());
          ps.setObject(2, transaction.id());
          ps.setObject(3, entry.accountId());
          ps.setString(4, entry.direction().name());
          ps.setLong(5, entry.nativeAmount().amountMinor());
          ps.setString(6, entry.nativeAmount().currency());
          ps.setLong(7, entry.baseAmount().amountMinor());
          ps.setString(8, entry.baseAmount().currency());
          ps.setBigDecimal(9, entry.fxRate());
        });
  }
}
