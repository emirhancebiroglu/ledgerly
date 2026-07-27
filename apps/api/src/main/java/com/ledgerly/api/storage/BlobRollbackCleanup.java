package com.ledgerly.api.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers a blob for deletion if the current transaction rolls back.
 *
 * <p>A blob is written before the row that references it commits, in every upload path in this
 * codebase (document, policy document). If the transaction rolls back after that write — e.g. an
 * audit insert fails — the blob would otherwise be orphaned forever, since its key never reaches a
 * committed row and nothing else ever reaps it. Registering the delete as an after-rollback
 * synchronization means it only fires when the transaction actually fails, never on a successful
 * commit.
 *
 * <p>Shared by {@code DocumentUploadService} and {@code PolicyUploadService} — was duplicated
 * per-service before M6 T8, which is exactly the drift the M5.1 backlog note about splitting
 * {@code DocumentUploadService} flagged.
 */
@Component
public class BlobRollbackCleanup {

  private final StorageClient storageClient;

  public BlobRollbackCleanup(StorageClient storageClient) {
    this.storageClient = storageClient;
  }

  /**
   * @param storageKey the blob to delete if the current transaction rolls back. The caller must
   *     always be {@code @Transactional}; outside a transaction there is nothing to roll back, so
   *     this is a no-op rather than an immediate delete or a spurious {@link IllegalStateException}
   *     from {@code registerSynchronization}.
   */
  public void registerOnRollback(String storageKey) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
              storageClient.delete(storageKey);
            }
          }
        });
  }
}
