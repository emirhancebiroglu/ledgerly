package com.ledgerly.api.auth;

import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, UUID organizationId) {}
