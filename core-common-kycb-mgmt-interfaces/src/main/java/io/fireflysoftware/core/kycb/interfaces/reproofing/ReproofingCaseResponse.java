package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;
import java.time.Instant;

public record ReproofingCaseResponse(UUID caseId, UUID providerStartId, ReproofingBinding binding, String status, String providerId, String policyId, String providerSessionId, Instant expiresAt, ReproofingAuthorization authorization) {
    @Override public String toString() { return "ReproofingCaseResponse[REDACTED]"; }
}
