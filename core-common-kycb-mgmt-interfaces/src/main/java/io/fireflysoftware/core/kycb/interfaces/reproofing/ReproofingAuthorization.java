package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.time.Instant;
import java.util.UUID;

public record ReproofingAuthorization(
        UUID caseId,
        UUID authorizationId,
        UUID executionId,
        ReproofingBinding binding,
        String evidenceDigest,
        String policyId,
        Instant expiresAt) {
    @Override
    public String toString() {
        return "ReproofingAuthorization[REDACTED]";
    }
}
