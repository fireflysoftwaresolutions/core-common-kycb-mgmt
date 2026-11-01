package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;

public record ReproofingNativeRequest(ReproofingBinding binding, UUID caseId, UUID executionId) {
    @Override
    public String toString() {
        return "ReproofingNativeRequest[REDACTED]";
    }
}
