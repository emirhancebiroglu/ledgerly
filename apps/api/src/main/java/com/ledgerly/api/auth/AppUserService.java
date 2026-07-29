package com.ledgerly.api.auth;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService {

  private final AppUserRepository appUserRepository;
  private final OrganizationRepository organizationRepository;
  private final OrganizationAccessGuard organizationAccessGuard;

  public AppUserService(
      AppUserRepository appUserRepository,
      OrganizationRepository organizationRepository,
      OrganizationAccessGuard organizationAccessGuard) {
    this.appUserRepository = appUserRepository;
    this.organizationRepository = organizationRepository;
    this.organizationAccessGuard = organizationAccessGuard;
  }

  @Transactional(readOnly = true)
  public AppUser getUser(UUID userId, AuthenticatedPrincipal principal) {
    AppUser user =
        appUserRepository.findById(userId).orElseThrow(NoSuchElementException::new);
    organizationAccessGuard.assertBelongsToOrganization(user.getOrganizationId(), principal);
    return user;
  }

  @Transactional(readOnly = true)
  public MeResponse currentProfile(AuthenticatedPrincipal principal) {
    AppUser user = getUser(principal.userId(), principal);
    Organization organization =
        organizationRepository.findById(user.getOrganizationId()).orElseThrow();
    return new MeResponse(
        user.getId(),
        user.getFullName(),
        user.getEmail(),
        organization.getId(),
        organization.getName(),
        organization.getBaseCurrency());
  }
}
