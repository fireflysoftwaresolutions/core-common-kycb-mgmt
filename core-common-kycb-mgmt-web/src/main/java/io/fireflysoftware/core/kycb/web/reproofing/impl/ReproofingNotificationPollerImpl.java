package io.fireflysoftware.core.kycb.web.reproofing.impl;

import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNotificationPort;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationQueue;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationSnapshot;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationSnapshot.State;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.ToDoubleFunction;

public final class ReproofingNotificationPollerImpl implements SmartLifecycle {
    private static final String PREFIX = "firefly.reproofing.notifications.";
    private final ReproofingNotificationPort port;
    private final ReproofingNotificationSettings settings;
    private final Clock clock;
    private final Counter successes, failures, processed;
    private final Map<String, Counter> failureCategories;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private Disposable.Swap subscription;
    private boolean running, active;
    private Instant lastAttempt, lastSuccess;
    private long consecutiveFailures;
    private ReproofingNotificationQueue queue;

    public ReproofingNotificationPollerImpl(ReproofingNotificationPort port,
            ReproofingNotificationSettings settings, MeterRegistry registry, Clock clock) {
        this.port = port;
        this.settings = settings;
        this.clock = clock;
        successes = registry.counter(PREFIX + "cycles", "outcome", "success");
        failures = registry.counter(PREFIX + "cycles", "outcome", "failure");
        processed = registry.counter(PREFIX + "processed");
        failureCategories = Map.of(
                "timeout", registry.counter(PREFIX + "failures", "category", "timeout"),
                "invalid_response", registry.counter(PREFIX + "failures", "category", "invalid_response"),
                "dependency", registry.counter(PREFIX + "failures", "category", "dependency"));
        gauge(registry, "active", s -> s.activeCycle() ? 1 : 0);
        gauge(registry, "consecutive_failures", ReproofingNotificationSnapshot::consecutiveFailures);
        gauge(registry, "last_success_epoch", s -> s.lastSuccessAt() == null ? 0 : s.lastSuccessAt().getEpochSecond());
        gauge(registry, "stale", s -> s.state() == State.STALE ? 1 : 0);
        gauge(registry, "observed", s -> s.queue() == null ? 0 : 1);
        for (String kind : new String[] {"pending", "sending", "retrying", "delivered", "failed", "invalid", "due", "oldest_due_delay_seconds"})
            Gauge.builder(PREFIX + "queue", this, p -> p.queueValue(kind))
                    .tag("kind", kind).register(registry);
    }
    private void gauge(MeterRegistry registry, String name, ToDoubleFunction<ReproofingNotificationSnapshot> value) {
        Gauge.builder(PREFIX + name, this, p -> value.applyAsDouble(p.snapshot())).register(registry);
    }
    private synchronized double queueValue(String kind) {
        if (queue == null) return Double.NaN;
        return switch (kind) {
            case "pending" -> queue.pending(); case "sending" -> queue.sending();
            case "retrying" -> queue.retrying(); case "delivered" -> queue.delivered();
            case "failed" -> queue.failed(); case "invalid" -> queue.invalid();
            case "due" -> queue.due(); default -> queue.oldestDueDelaySeconds();
        };
    }
    public synchronized ReproofingNotificationSnapshot snapshot() {
        State state;
        if (consecutiveFailures > 0) state = State.UNAVAILABLE;
        else if (lastSuccess == null) state = State.STARTING;
        else if (clock.instant().isAfter(lastSuccess.plus(settings.staleAfter()))) state = State.STALE;
        else if (queue.failed() > 0 || queue.invalid() > 0 || queue.retrying() > 0
                || queue.oldestDueDelaySeconds() > settings.overdueAfter().toSeconds()) state = State.DEGRADED;
        else state = State.HEALTHY;
        return new ReproofingNotificationSnapshot(state, active, lastAttempt, lastSuccess, consecutiveFailures, queue);
    }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "reproofing-notifications");
            thread.setDaemon(true);
            return thread;
        });
        future = scheduler.schedule(this::cycle, 0, TimeUnit.MILLISECONDS);
    }
    private void cycle() {
        final Disposable.Swap current;
        synchronized (this) {
            if (!running || active) return;
            active = true;
            lastAttempt = clock.instant();
            current = Disposables.swap();
            subscription = current;
        }
        current.update(Mono.defer(port::dispatchOne)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid notification response")))
                .flatMap(count -> {
                    if (count < 0 || count > 1) return Mono.error(new IllegalArgumentException("Invalid notification response"));
                    return Mono.defer(port::status)
                            .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid notification response")))
                            .doOnNext(q -> success(q, count));
                })
                .timeout(settings.requestTimeout())
                .doOnError(this::failure)
                .doFinally(signal -> finish(current))
                .subscribe(ignored -> {}, ignored -> {}));
    }
    private synchronized void success(ReproofingNotificationQueue observed, int count) {
        Instant now = clock.instant();
        if (observed.observedAt().isAfter(now.plusSeconds(30)) || observed.observedAt().isBefore(now.minusSeconds(90)))
            throw new IllegalArgumentException("Invalid notification response");
        queue = observed;
        lastSuccess = now;
        consecutiveFailures = 0;
        processed.increment(count);
        successes.increment();
    }
    private synchronized void failure(Throwable error) {
        if (consecutiveFailures < Long.MAX_VALUE) consecutiveFailures++;
        failures.increment();
        String category = error instanceof TimeoutException ? "timeout"
                : error instanceof IllegalArgumentException ? "invalid_response" : "dependency";
        failureCategories.get(category).increment();
    }
    private synchronized void finish(Disposable.Swap current) {
        if (subscription != current) return;
        active = false;
        subscription = null;
        if (running) future = scheduler.schedule(this::cycle, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void stop() {
        running = false;
        if (future != null) future.cancel(false);
        if (subscription != null) subscription.dispose();
        if (scheduler != null) scheduler.shutdownNow();
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public synchronized boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
