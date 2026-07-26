package com.ledgerly.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Calls {@link AppUserService} directly — no MockMvc, no controller, no HTTP filter chain — to
 * prove the org-scoped guard rejects cross-tenant access at the service layer itself, per T3.
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

  @Mock private AppUserRepository appUserRepository;

  private final OrganizationAccessGuard organizationAccessGuard =
      spy(new OrganizationAccessGuard());

  @Test
  void servicePrincipalFromOrgACannotReadUserOwnedByOrgB() {
    AppUserService service = new AppUserService(appUserRepository, organizationAccessGuard);

    UUID orgAId = UUID.randomUUID();
    UUID orgBId = UUID.randomUUID();
    UUID userIdInOrgB = UUID.randomUUID();
    AppUser userInOrgB = new AppUser(orgBId, "victim@example.com", "hash");
    when(appUserRepository.findById(userIdInOrgB)).thenReturn(Optional.of(userInOrgB));

    AuthenticatedPrincipal principalFromOrgA = new AuthenticatedPrincipal(UUID.randomUUID(), orgAId);

    assertThatThrownBy(() -> service.getUser(userIdInOrgB, principalFromOrgA))
        .isInstanceOf(CrossOrganizationAccessException.class);
  }

  @Test
  void servicePrincipalFromSameOrgCanReadTheUser() {
    AppUserService service = new AppUserService(appUserRepository, organizationAccessGuard);

    UUID orgId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    AppUser user = new AppUser(orgId, "owner@example.com", "hash");
    when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));

    AuthenticatedPrincipal principalFromSameOrg = new AuthenticatedPrincipal(UUID.randomUUID(), orgId);

    assertThat(service.getUser(userId, principalFromSameOrg)).isSameAs(user);
    verify(organizationAccessGuard).assertBelongsToOrganization(orgId, principalFromSameOrg);
  }
}
