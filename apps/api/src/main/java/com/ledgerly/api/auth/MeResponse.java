package com.ledgerly.api.auth;

import java.util.UUID;

public record MeResponse(
    UUID userId,
    String fullName,
    String email,
    UUID organizationId,
    String organizationName,
    String baseCurrency) {}
