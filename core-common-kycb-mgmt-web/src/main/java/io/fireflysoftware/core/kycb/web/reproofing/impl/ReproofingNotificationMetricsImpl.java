package io.fireflysoftware.core.kycb.web.reproofing.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public final class ReproofingNotificationMetricsImpl implements AutoCloseable {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    MeterRegistry registry() { return registry; }
    public String scrape() { return registry.scrape(); }
    @Override public void close() { registry.close(); }
}
