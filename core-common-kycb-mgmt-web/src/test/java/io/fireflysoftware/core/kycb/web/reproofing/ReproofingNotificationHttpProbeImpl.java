package io.fireflysoftware.core.kycb.web.reproofing;

import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationClientImpl;
import java.time.Duration;

public final class ReproofingNotificationHttpProbeImpl {
    public static void main(String[] args) {
        var client = new ReproofingNotificationClientImpl(args[0], "notification", "notification-fixture-" + "x".repeat(40), Duration.ofSeconds(1));
        if ("success".equals(args[1])) {
            if (client.dispatchOne().block() != 1 || client.status().block().delivered() != 2) throw new AssertionError("Valid HTTPS wire rejected");
        } else {
            try { client.status().block(); throw new AssertionError("Unsafe response accepted"); }
            catch (RuntimeException error) {
                if (error.getMessage() == null || error.getMessage().contains("private-provider-detail")) throw new AssertionError("Unsafe diagnostics");
            }
        }
    }
}
