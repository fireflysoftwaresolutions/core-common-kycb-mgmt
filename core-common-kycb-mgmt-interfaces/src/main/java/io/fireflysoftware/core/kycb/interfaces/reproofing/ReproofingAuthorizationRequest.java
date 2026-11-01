package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;

public record ReproofingAuthorizationRequest(
        ReproofingBinding binding, UUID authorizationId, UUID executionId) {
    @Override
    public String toString() {
        return "ReproofingAuthorizationRequest[REDACTED]";
    }
}
