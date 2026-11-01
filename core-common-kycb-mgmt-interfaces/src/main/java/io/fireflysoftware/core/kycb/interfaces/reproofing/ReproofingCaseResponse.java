package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.time.Instant;
import java.util.UUID;

public record ReproofingCaseResponse(
        UUID caseId,
        UUID providerStartId,
        ReproofingBinding binding,
        String status,
        String providerId,
        String policyId,
        String providerSessionId,
        Instant expiresAt,
        ReproofingAuthorization authorization) {
    @Override
    public String toString() {
        return "ReproofingCaseResponse[REDACTED]";
    }
}
