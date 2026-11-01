package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReproofingNotificationPresenceTest {
    @Test
    void managedNotificationWorkerAndPrivateMonitorAreProductOwned() {
        for (String name : new String[] {
            "io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationPollerImpl",
            "io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationConfigurationImpl",
            "io.fireflysoftware.core.kycb.web.reproofing.ReproofingNotificationController"}) {
            assertDoesNotThrow(() -> Class.forName(name), "Missing product-owned notification component: " + name);
        }
    }
}
