package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.time.Instant;

public record ReproofingAdmission(ReproofingBinding binding, boolean eligible, Instant expiresAt) {
    @Override public String toString() { return "ReproofingAdmission[REDACTED]"; }
}
