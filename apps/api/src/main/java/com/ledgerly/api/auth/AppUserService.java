package com.ledgerly.api.auth;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService {

  private final AppUserRepository appUserRepository;
  private final OrganizationAccessGuard organizationAccessGuard;

  public AppUserService(
      AppUserRepository appUserRepository, OrganizationAccessGuard organizationAccessGuard) {
    this.appUserRepository = appUserRepository;
    this.organizationAccessGuard = organizationAccessGuard;
  }

  @Transactional(readOnly = true)
  public AppUser getUser(UUID userId, AuthenticatedPrincipal principal) {
    AppUser user =
        appUserRepository.findById(userId).orElseThrow(NoSuchElementException::new);
    organizationAccessGuard.assertBelongsToOrganization(user.getOrganizationId(), principal);
    return user;
  }
}
