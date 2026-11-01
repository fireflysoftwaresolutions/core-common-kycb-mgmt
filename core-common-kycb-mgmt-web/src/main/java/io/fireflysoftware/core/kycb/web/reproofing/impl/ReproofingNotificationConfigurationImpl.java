package io.fireflysoftware.core.kycb.web.reproofing.impl;

import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNotificationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"firefly.reproofing.enabled", "firefly.reproofing.notifications.enabled"}, havingValue = "true")
public class ReproofingNotificationConfigurationImpl {
    @Bean public ReproofingNotificationSettings reproofingNotificationSettings(Environment env) {
        String prefix = "firefly.reproofing.notifications.";
        return new ReproofingNotificationSettings(
                Duration.parse(env.getProperty(prefix + "poll-interval", "PT30S")),
                Duration.parse(env.getProperty(prefix + "request-timeout", "PT30S")),
                Duration.parse(env.getProperty(prefix + "stale-after", "PT90S")),
                Duration.parse(env.getProperty(prefix + "overdue-after", "PT2M")));
    }
    @Bean public ReproofingNotificationPort reproofingNotificationPort(Environment env) {
        var credential = ReproofingNotificationCredentialImpl.validated(env)[0];
        return new ReproofingNotificationClientImpl(env.getRequiredProperty("firefly.reproofing.native.base-url"),
                credential.identity, credential.secret, reproofingNotificationSettings(env).requestTimeout());
    }
    @Bean(destroyMethod = "close") public ReproofingNotificationMetricsImpl reproofingNotificationMetrics() {
        return new ReproofingNotificationMetricsImpl();
    }
    @Bean public ReproofingNotificationPollerImpl reproofingNotificationPoller(ReproofingNotificationPort port,
            ReproofingNotificationSettings settings, ReproofingNotificationMetricsImpl metrics) {
        return new ReproofingNotificationPollerImpl(port, settings, metrics.registry(), Clock.systemUTC());
    }
}
