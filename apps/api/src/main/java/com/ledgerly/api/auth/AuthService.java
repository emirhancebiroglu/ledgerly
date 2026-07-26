package com.ledgerly.api.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  /** Bcrypt hash of a value nobody can guess, spent verifying against an unknown email so the
   * unknown-email path costs the same time as a wrong password. */
  private static final String DUMMY_HASH =
      "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5UbUCzM9k28Om.pBbLRJEZK99f3Ni";

  private final AppUserRepository appUserRepository;
  private final OrganizationRepository organizationRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;

  public AuthService(
      AppUserRepository appUserRepository,
      OrganizationRepository organizationRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      RefreshTokenService refreshTokenService) {
    this.appUserRepository = appUserRepository;
    this.organizationRepository = organizationRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
  }

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    Organization organization = organizationRepository.save(
        new Organization(request.organizationName(), "EUR"));
    AppUser user = appUserRepository.save(
        new AppUser(
            organization.getId(), request.email(), passwordEncoder.encode(request.password())));
    return issueTokens(user);
  }

  @Transactional
  public AuthResponse login(LoginRequest request) {
    AppUser user = appUserRepository.findByEmail(request.email()).orElse(null);
    String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_HASH;
    boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

    if (user == null || !matches) {
      throw new InvalidCredentialsException();
    }
    return issueTokens(user);
  }

  @Transactional
  public AuthResponse refresh(RefreshRequest request) {
    RefreshToken redeemed = refreshTokenService.redeem(request.refreshToken());
    AppUser user =
        appUserRepository.findById(redeemed.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
    return issueTokens(user);
  }

  private AuthResponse issueTokens(AppUser user) {
    String accessToken = jwtService.issueAccessToken(user.getId(), user.getOrganizationId());
    String refreshToken = refreshTokenService.issue(user.getId());
    return new AuthResponse(accessToken, refreshToken);
  }
}
