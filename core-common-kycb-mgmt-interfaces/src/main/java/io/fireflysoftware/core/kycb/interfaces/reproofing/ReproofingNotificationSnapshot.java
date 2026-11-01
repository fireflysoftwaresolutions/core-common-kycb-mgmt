package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.time.Instant;

public record ReproofingNotificationSnapshot(
        State state, boolean activeCycle, Instant lastAttemptAt, Instant lastSuccessAt,
        long consecutiveFailures, ReproofingNotificationQueue queue) {
    public enum State { STARTING, HEALTHY, DEGRADED, UNAVAILABLE, STALE }
}
