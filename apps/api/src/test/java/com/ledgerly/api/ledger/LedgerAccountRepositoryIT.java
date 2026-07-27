package com.ledgerly.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LedgerAccountRepository#findOrCreate}: two concurrent first-uses of the same account
 * name in the same org must not 500. Before the {@code ON CONFLICT} fix, the loser's insert threw
 * {@link org.springframework.dao.DuplicateKeyException}, which was caught — but in Postgres a
 * failed statement aborts the whole enclosing transaction, so the catch block's own recovery
 * lookup ran inside that aborted transaction and threw "current transaction is aborted" instead
 * of finding the winner's row.
 */
class LedgerAccountRepositoryIT extends AbstractPostgresIT {

  @Autowired private LedgerAccountRepository ledgerAccountRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void concurrentFirstUseOfTheSameAccountNameDoesNotFail() throws Exception {
    UUID org;
    try (Connection connection = dataSourceConnection()) {
      org = insertOrganization(connection);
    }

    // Mirrors how findOrCreate is actually called: inside a caller's @Transactional, not as its
    // own top-level transaction. That's what makes the ON CONFLICT fix necessary -- a caught
    // DuplicateKeyException still leaves the enclosing transaction aborted for every statement
    // after it.
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Callable<UUID> createExpenseAccount =
        () ->
            transactionTemplate.execute(
                status -> ledgerAccountRepository.findOrCreate(org, "Travel", "EXPENSE", "EUR"));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<UUID>> futures =
          executor.invokeAll(List.of(createExpenseAccount, createExpenseAccount));
      UUID first = futures.get(0).get();
      UUID second = futures.get(1).get();
      assertThat(first).isEqualTo(second);
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private Connection dataSourceConnection() throws Exception {
    return POSTGRES.createConnection("");
  }
}
