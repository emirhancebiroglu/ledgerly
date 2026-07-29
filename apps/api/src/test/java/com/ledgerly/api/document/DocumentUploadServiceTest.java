package com.ledgerly.api.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.ratelimit.UploadRateLimiter;
import com.ledgerly.api.storage.BlobRollbackCleanup;
import com.ledgerly.api.storage.StorageClient;
import com.ledgerly.api.storage.StorageException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A storage-layer failure must surface before anything is written to the {@code document} table —
 * proves the ordering `store` (may throw) then {@code documentRepository.save}, not the reverse.
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private StorageClient storageClient;
  @Mock private BlobRollbackCleanup blobRollbackCleanup;
  @Mock private AuditService auditService;
  @Mock private UploadRateLimiter uploadRateLimiter;
  @Mock private DocumentActivityService documentActivityService;

  private static final byte[] REAL_PDF =
      ("%PDF-1.7\n" + "0".repeat(512) + "\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @Test
  void aStorageFailureBeforeTheRowIsWrittenLeavesNoRowAndPropagates() {
    when(storageClient.store(any())).thenThrow(new StorageException("disk full", null));
    DocumentUploadService service =
        new DocumentUploadService(
            documentRepository,
            storageClient,
            blobRollbackCleanup,
            auditService,
            new ObjectMapper(),
            uploadRateLimiter,
            documentActivityService,
            10_485_760);
    AuthenticatedPrincipal principal = new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> service.upload(REAL_PDF, "invoice.pdf", principal))
        .isInstanceOf(StorageException.class);

    verify(documentRepository, never()).save(any());
    verifyNoInteractions(auditService);
  }
}
