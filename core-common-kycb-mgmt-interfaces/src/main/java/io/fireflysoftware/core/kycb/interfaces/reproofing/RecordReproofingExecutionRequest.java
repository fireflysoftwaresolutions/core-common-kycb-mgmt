package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;

public record RecordReproofingExecutionRequest(ReproofingBinding binding, UUID authorizationId, UUID executionId, String outcome) {
    @Override public String toString() { return "RecordReproofingExecutionRequest[REDACTED]"; }
}
