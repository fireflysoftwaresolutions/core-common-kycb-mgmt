package io.fireflysoftware.core.kycb.web.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationSnapshot;
import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationPollerImpl;
import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingNotificationMetricsImpl;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@ConditionalOnProperty(name = {"firefly.reproofing.enabled", "firefly.reproofing.notifications.enabled"}, havingValue = "true")
public class ReproofingNotificationController {
    private final ReproofingNotificationPollerImpl poller;
    private final ReproofingNotificationMetricsImpl metrics;
    public ReproofingNotificationController(ReproofingNotificationPollerImpl poller, ReproofingNotificationMetricsImpl metrics) {
        this.poller = poller;
        this.metrics = metrics;
    }
    @GetMapping("/internal/identity-recovery/notifications")
    public ReproofingNotificationSnapshot status() { return poller.snapshot(); }
    @GetMapping(value = "/internal/identity-recovery/notifications/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String metrics() { return metrics.scrape(); }
}
