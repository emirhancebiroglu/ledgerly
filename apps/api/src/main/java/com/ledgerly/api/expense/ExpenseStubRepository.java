package com.ledgerly.api.expense;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseStubRepository extends JpaRepository<ExpenseStub, UUID> {

  long countByOrganizationId(UUID organizationId);
}
