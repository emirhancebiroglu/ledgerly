package com.ledgerly.api.expense;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  Optional<Expense> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<Expense> findByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);
}
