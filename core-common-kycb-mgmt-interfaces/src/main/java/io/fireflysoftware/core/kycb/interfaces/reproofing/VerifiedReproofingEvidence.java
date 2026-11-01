package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;
import java.time.Instant;

public record VerifiedReproofingEvidence(UUID caseId, ReproofingBinding binding, String providerId, String policyId, String providerSessionId, String evidenceId, String evidenceDigest, UUID referenceId, Instant verifiedAt, Instant expiresAt, boolean documentAuthentic, boolean liveMatch, boolean fraudCleared, boolean referenceMatched) {
    @Override public String toString() { return "VerifiedReproofingEvidence[REDACTED]"; }
}
