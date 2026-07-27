package com.ledgerly.api.category;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerly.api.audit.AuditService;
import com.ledgerly.api.auth.AuthenticatedPrincipal;
import com.ledgerly.api.correlation.CorrelationIds;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

  private final CategoryRepository categoryRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public CategoryService(
      CategoryRepository categoryRepository,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.categoryRepository = categoryRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Category create(String name, AuthenticatedPrincipal principal) {
    if (categoryRepository.existsByOrganizationIdAndName(principal.organizationId(), name)) {
      throw new DuplicateCategoryNameException(name);
    }
    Category category = categoryRepository.save(new Category(principal.organizationId(), name));
    categoryRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "CREATE",
        "category",
        category.getId(),
        null,
        auditPayload(category),
        CorrelationIds.current());

    return category;
  }

  @Transactional(readOnly = true)
  public List<Category> list(AuthenticatedPrincipal principal) {
    return categoryRepository.findByOrganizationIdOrderByNameAsc(principal.organizationId());
  }

  @Transactional(readOnly = true)
  public Category get(UUID id, AuthenticatedPrincipal principal) {
    return findForOrganization(id, principal);
  }

  @Transactional
  public Category rename(UUID id, String name, AuthenticatedPrincipal principal) {
    Category category = findForOrganization(id, principal);
    if (categoryRepository.existsByOrganizationIdAndNameAndIdNot(
        principal.organizationId(), name, id)) {
      throw new DuplicateCategoryNameException(name);
    }
    String before = auditPayload(category);
    category.rename(name);
    categoryRepository.flush();

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "RENAME",
        "category",
        category.getId(),
        before,
        auditPayload(category),
        CorrelationIds.current());

    return category;
  }

  @Transactional
  public void delete(UUID id, AuthenticatedPrincipal principal) {
    Category category = findForOrganization(id, principal);
    String before = auditPayload(category);
    categoryRepository.delete(category);
    try {
      categoryRepository.flush();
    } catch (DataIntegrityViolationException e) {
      // No ON DELETE action on expense.category_id: an expense still referencing this category
      // is a client-visible conflict, not a server fault. flush() is what actually runs the
      // DELETE against the database — delete() alone only stages it in the persistence context.
      throw new CategoryInUseException(
          "Category cannot be deleted while an expense still references it: " + id);
    }

    auditService.record(
        principal.organizationId(),
        principal.userId(),
        "DELETE",
        "category",
        category.getId(),
        before,
        null,
        CorrelationIds.current());
  }

  private Category findForOrganization(UUID id, AuthenticatedPrincipal principal) {
    return categoryRepository
        .findByIdAndOrganizationId(id, principal.organizationId())
        .orElseThrow(() -> new NoSuchElementException("Category not found: " + id));
  }

  private String auditPayload(Category category) {
    try {
      return objectMapper.writeValueAsString(Map.of("name", category.getName()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize category for audit trail", e);
    }
  }
}
