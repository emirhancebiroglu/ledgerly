package com.ledgerly.api.expense;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  Optional<Expense> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<Expense> findByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);

  List<Expense> findByOrganizationId(UUID organizationId, Pageable pageable);

  List<Expense> findByOrganizationIdAndStatus(
      UUID organizationId, ExpenseStatus status, Pageable pageable);

  List<Expense> findByOrganizationIdAndVendorIgnoreCaseContaining(
      UUID organizationId, String vendor, Pageable pageable);

  List<Expense> findByOrganizationIdAndStatusAndVendorIgnoreCaseContaining(
      UUID organizationId, ExpenseStatus status, String vendor, Pageable pageable);
}
