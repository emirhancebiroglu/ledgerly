package com.ledgerly.api.audit;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes an audit row using whatever transaction is already open on the caller — deliberately no
 * {@code @Transactional} here. The whole point of the audit trail is that it commits or rolls
 * back atomically with the business change it describes, so this must join the caller's
 * transaction, never start its own.
 */
@Service
public class AuditService {

  private final AuditLogRepository auditLogRepository;

  public AuditService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  public void record(
      UUID organizationId,
      UUID actor,
      String action,
      String entityType,
      UUID entityId,
      String beforeJson,
      String afterJson,
      UUID correlationId) {
    auditLogRepository.save(
        new AuditLog(
            organizationId, actor, action, entityType, entityId, beforeJson, afterJson, correlationId));
  }
}
