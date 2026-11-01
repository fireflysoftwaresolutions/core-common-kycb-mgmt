package io.fireflysoftware.core.kycb.web.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationSnapshot;
import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationPollerImpl;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@ConditionalOnProperty(name = {"firefly.reproofing.enabled", "firefly.reproofing.notifications.enabled"}, havingValue = "true")
public class ReproofingNotificationController {
    private final ReproofingNotificationPollerImpl poller;
    public ReproofingNotificationController(ReproofingNotificationPollerImpl poller) { this.poller = poller; }
    @GetMapping("/internal/identity-recovery/notifications")
    public ReproofingNotificationSnapshot status() { return poller.snapshot(); }
}
