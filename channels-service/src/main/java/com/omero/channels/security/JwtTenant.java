package com.omero.channels.security;

import java.util.UUID;

public record JwtTenant(UUID userId, UUID tenantId) {}
