package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;
import java.time.Instant;

public record ReproofingAuthorization(UUID caseId, UUID authorizationId, UUID executionId, ReproofingBinding binding, String evidenceDigest, String policyId, Instant expiresAt) {
    @Override public String toString() { return "ReproofingAuthorization[REDACTED]"; }
}
