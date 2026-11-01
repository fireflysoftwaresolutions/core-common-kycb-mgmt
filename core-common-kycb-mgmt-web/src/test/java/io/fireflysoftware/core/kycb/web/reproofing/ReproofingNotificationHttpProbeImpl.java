package io.fireflysoftware.core.kycb.web.reproofing;

import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationClientImpl;
import java.time.Duration;

public final class ReproofingNotificationHttpProbeImpl {
    public static void main(String[] args) {
        var client = new ReproofingNotificationClientImpl(args[0], "notification", "notification-fixture-" + "x".repeat(40), Duration.ofSeconds(30));
        if (client.dispatchOne().block(Duration.ofSeconds(35)) != 1) throw new AssertionError("Valid warmup dispatch rejected");
        if ("success".equals(args[1])) {
            if (client.status().block(Duration.ofSeconds(35)).delivered() != 2) throw new AssertionError("Valid HTTPS wire rejected");
            return;
        }
        boolean timeout = "timeout".equals(args[1]);
        var target = timeout ? new ReproofingNotificationClientImpl(args[0], "notification", "notification-fixture-" + "x".repeat(40), Duration.ofSeconds(1)) : client;
        long started = System.nanoTime();
        try { target.status().block(Duration.ofSeconds(timeout ? 5 : 35)); throw new AssertionError("Unsafe response accepted"); }
        catch (RuntimeException error) {
            Class<?> expected = "invalid".equals(args[1]) ? IllegalArgumentException.class : IllegalStateException.class;
            String message = "invalid".equals(args[1]) ? "Invalid notification response" : "Notification dependency unavailable";
            if (error.getClass() != expected || !message.equals(error.getMessage())) throw new AssertionError("Unexpected failure category", error);
            long elapsed = System.nanoTime() - started;
            if (!timeout && elapsed >= Duration.ofSeconds(30).toNanos())
                throw new AssertionError("Fixture rejection was replaced by a request timeout");
            if (timeout) {
                if (elapsed < Duration.ofMillis(750).toNanos() || elapsed >= Duration.ofSeconds(5).toNanos())
                    throw new AssertionError("One-second response deadline was not observed");
            }
        }
    }
}
