package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;
import java.time.Instant;

public record ReproofingExecution(UUID caseId, UUID executionId, ReproofingBinding binding, String outcome, Instant committedAt) {
    @Override public String toString() { return "ReproofingExecution[REDACTED]"; }
}
