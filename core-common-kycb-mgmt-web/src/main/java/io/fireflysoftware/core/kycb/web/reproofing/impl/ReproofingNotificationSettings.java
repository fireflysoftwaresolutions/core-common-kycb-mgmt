package io.fireflysoftware.core.kycb.web.reproofing.impl;

import java.time.Duration;

public record ReproofingNotificationSettings(
        Duration pollInterval, Duration requestTimeout, Duration staleAfter, Duration overdueAfter) {
    public ReproofingNotificationSettings {
        if (!bounded(pollInterval, 300) || !bounded(requestTimeout, 60)
                || !bounded(staleAfter, 3600) || !bounded(overdueAfter, 3600)
                || staleAfter.compareTo(pollInterval.plus(requestTimeout)) <= 0)
            throw new IllegalArgumentException("Invalid notification timing configuration");
    }
    private static boolean bounded(Duration value, int max) {
        return value != null && value.compareTo(Duration.ofSeconds(1)) >= 0
                && value.compareTo(Duration.ofSeconds(max)) <= 0;
    }
}
