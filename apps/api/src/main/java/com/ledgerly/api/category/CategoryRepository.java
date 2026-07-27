package com.ledgerly.api.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

  /**
   * The only lookup by id callers should use. Taking the organization as part of the query means a
   * category belonging to another tenant is indistinguishable from one that does not exist.
   */
  Optional<Category> findByIdAndOrganizationId(UUID id, UUID organizationId);

  List<Category> findByOrganizationIdOrderByNameAsc(UUID organizationId);

  boolean existsByOrganizationIdAndName(UUID organizationId, String name);

  boolean existsByOrganizationIdAndNameAndIdNot(UUID organizationId, String name, UUID id);
}
