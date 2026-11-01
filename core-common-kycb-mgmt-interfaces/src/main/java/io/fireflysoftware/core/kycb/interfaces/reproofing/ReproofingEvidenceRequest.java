package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;

public record ReproofingEvidenceRequest(UUID caseId, ReproofingBinding binding, String providerId, String policyId, String providerSessionId) {
    @Override public String toString() { return "ReproofingEvidenceRequest[REDACTED]"; }
}
