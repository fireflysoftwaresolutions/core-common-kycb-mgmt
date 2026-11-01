package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.time.Instant;
import java.util.Objects;

public record ReproofingNotificationQueue(
        Instant observedAt, long pending, long sending, long retrying, long delivered,
        long failed, long invalid, long due, long oldestDueDelaySeconds) {
    public ReproofingNotificationQueue {
        Objects.requireNonNull(observedAt);
        try {
            long total = 0;
            for (long count : new long[] {pending, sending, delivered, failed, invalid}) {
                if (count < 0) throw new IllegalArgumentException("Invalid notification aggregate");
                total = Math.addExact(total, count);
            }
            if (retrying < 0 || retrying > pending || due < 0
                    || due > Math.addExact(pending, sending) || oldestDueDelaySeconds < 0
                    || (due == 0 && oldestDueDelaySeconds != 0))
                throw new IllegalArgumentException("Invalid notification aggregate");
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Invalid notification aggregate");
        }
    }
}
