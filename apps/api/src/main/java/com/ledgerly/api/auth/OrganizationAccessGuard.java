package com.ledgerly.api.auth;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Org-scoped authorization check, callable from any service method independent of the HTTP
 * layer — a controller filter alone cannot prove isolation between tenants.
 */
@Component
public class OrganizationAccessGuard {

  public void assertBelongsToOrganization(UUID resourceOrganizationId, AuthenticatedPrincipal principal) {
    if (!resourceOrganizationId.equals(principal.organizationId())) {
      throw new CrossOrganizationAccessException();
    }
  }
}
