package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LedgerTransactionRepository#findEntriesByTransactionId}: M7a T4's expense-detail read
 * model calls this org-scoped, but the method itself must refuse to serve another org's entries
 * even if a future caller forgets to pre-validate the expense first.
 */
class LedgerTransactionRepositoryIT extends AbstractPostgresIT {

  @Autowired private LedgerTransactionRepository ledgerTransactionRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void anotherOrganizationsTransactionIdReturnsNoEntries() {
    UUID orgA = insertOrganization();
    UUID orgB = insertOrganization();
    UUID debitAccount = insertAccount(orgA, "Travel");
    UUID creditAccount = insertAccount(orgA, "Accounts Payable");

    UUID transactionId =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  UUID id = insertTransaction(orgA);
                  insertEntry(id, debitAccount, "DEBIT", 1000);
                  insertEntry(id, creditAccount, "CREDIT", 1000);
                  return id;
                });

    List<LedgerEntryView> asOwningOrg =
        ledgerTransactionRepository.findEntriesByTransactionId(transactionId, orgA);
    List<LedgerEntryView> asOtherOrg =
        ledgerTransactionRepository.findEntriesByTransactionId(transactionId, orgB);

    assertThat(asOwningOrg).hasSize(2);
    assertThat(asOtherOrg).isEmpty();
  }

  private UUID insertOrganization() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO organization (id, name, base_currency) VALUES (?, ?, 'EUR')",
        id,
        "org-" + id);
    return id;
  }

  private UUID insertAccount(UUID orgId, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO account (id, organization_id, name, account_type, currency) "
            + "VALUES (?, ?, ?, 'EXPENSE', 'EUR')",
        id,
        orgId,
        name);
    return id;
  }

  private UUID insertTransaction(UUID orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO ledger_transaction (id, organization_id, base_currency, posted_at) "
            + "VALUES (?, ?, 'EUR', now())",
        id,
        orgId);
    return id;
  }

  private void insertEntry(UUID transactionId, UUID accountId, String direction, long amountMinor) {
    jdbcTemplate.update(
        "INSERT INTO ledger_entry (id, transaction_id, account_id, direction, "
            + "native_amount_minor, native_currency, base_amount_minor, base_currency, fx_rate) "
            + "VALUES (?, ?, ?, ?, ?, 'EUR', ?, 'EUR', 1)",
        UUID.randomUUID(),
        transactionId,
        accountId,
        direction,
        amountMinor,
        amountMinor);
  }
}
