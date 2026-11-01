package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNotificationPort;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationQueue;
import io.fireflysoftware.core.kycb.web.reproofing.impl.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

class ReproofingNotificationPollerTest {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T12:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    final ReproofingNotificationSettings settings = new ReproofingNotificationSettings(
        Duration.ofSeconds(300), Duration.ofSeconds(1), Duration.ofSeconds(302), Duration.ofSeconds(120));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReproofingNotificationQueue queue(long pending, long retrying, long delivered, long failed, long invalid, long due, long delay) {
        return new ReproofingNotificationQueue(now.get(),pending,0,retrying,delivered,failed,invalid,due,delay);
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Worker did not reach expected state");
    }
    ReproofingNotificationPort port(Mono<Integer> dispatch, Mono<ReproofingNotificationQueue> status) {
        return new ReproofingNotificationPort() {
            public Mono<Integer> dispatchOne() { return dispatch; }
            public Mono<ReproofingNotificationQueue> status() { return status; }
        };
    }
    @Test void immediateCycleUsesImmutableSnapshotAndMetricsBecomeStaleWithoutAnotherCycle() throws Exception {
        var worker = new ReproofingNotificationPollerImpl(port(Mono.just(1),Mono.just(queue(0,0,1,0,0,0,0))),settings,registry,clock);
        assertEquals("STARTING",worker.snapshot().state().name());
        assertFalse(worker.isRunning());
        try {
            worker.start(); await(() -> worker.snapshot().lastSuccessAt()!=null && !worker.snapshot().activeCycle());
            var saved=worker.snapshot();
            assertEquals("HEALTHY",saved.state().name());
            assertEquals(1,saved.queue().delivered());
            assertEquals(1,registry.get("firefly.reproofing.notifications.processed").counter().count());
            assertEquals(1,registry.get("firefly.reproofing.notifications.cycles").tag("outcome","success").counter().count());
            now.set(now.get().plusSeconds(303));
            assertEquals("STALE",worker.snapshot().state().name());
            assertEquals("HEALTHY",saved.state().name());
            assertEquals(1,registry.get("firefly.reproofing.notifications.stale").gauge().value());
            assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).allMatch(t -> java.util.Set.of("outcome","category","kind").contains(t.getKey())));
        } finally { worker.stop(); }
    }
    @Test void shutdownCancelsActivePublisherAndReleasesGateWithoutManufacturingSuccess() throws Exception {
        var cancelled=new AtomicBoolean(); var calls=new AtomicInteger();
        var pending=Mono.<Integer>never().doOnSubscribe(s->calls.incrementAndGet()).doOnCancel(()->cancelled.set(true));
        var worker=new ReproofingNotificationPollerImpl(port(pending,Mono.just(queue(0,0,0,0,0,0,0))),settings,registry,clock);
        worker.start(); await(()->worker.snapshot().activeCycle() && calls.get()==1);
        worker.start(); Thread.sleep(50); assertEquals(1,calls.get());
        worker.stop(); await(()->!worker.snapshot().activeCycle());
        assertTrue(cancelled.get()); assertFalse(worker.isRunning()); assertNull(worker.snapshot().lastSuccessAt());
        assertEquals(0,registry.get("firefly.reproofing.notifications.cycles").tag("outcome","success").counter().count());
    }
    @Test void wholeCycleTimeoutIsUnavailableAndNextStartCanReobserveSameQueue() throws Exception {
        var dispatches=new AtomicInteger();
        var worker=new ReproofingNotificationPollerImpl(port(Mono.defer(()->dispatches.incrementAndGet()==1?Mono.never():Mono.just(0)),Mono.just(queue(0,0,0,1,0,0,0))),settings,registry,clock);
        try {
            worker.start(); await(()->worker.snapshot().consecutiveFailures()==1 && !worker.snapshot().activeCycle());
            assertEquals("UNAVAILABLE",worker.snapshot().state().name()); assertNull(worker.snapshot().queue());
            assertEquals(1,registry.get("firefly.reproofing.notifications.failures").tag("category","timeout").counter().count());
            worker.stop(); worker.start(); await(()->worker.snapshot().lastSuccessAt()!=null && !worker.snapshot().activeCycle());
            assertEquals("DEGRADED",worker.snapshot().state().name()); assertEquals(1,worker.snapshot().queue().failed());
            assertEquals(0,worker.snapshot().consecutiveFailures());
        } finally {worker.stop();}
    }
    @Test void invalidCountsAndUntrustedNativeClockCannotBecomeHealthy() throws Exception {
        assertThrows(IllegalArgumentException.class,()->queue(1,2,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->queue(0,0,0,0,0,0,1));
        assertThrows(IllegalArgumentException.class,()->new ReproofingNotificationQueue(now.get(),Long.MAX_VALUE,1,0,0,0,0,0,0));
        for (long offset:new long[]{31,-91}) {
            var q=new ReproofingNotificationQueue(now.get().plusSeconds(offset),0,0,0,0,0,0,0,0);
            var worker=new ReproofingNotificationPollerImpl(port(Mono.just(0),Mono.just(q)),settings,new SimpleMeterRegistry(),clock);
            try {worker.start();await(()->worker.snapshot().consecutiveFailures()==1);assertEquals("UNAVAILABLE",worker.snapshot().state().name());assertNull(worker.snapshot().queue());}
            finally {worker.stop();}
        }
    }
    @Test void retryingAndOverdueRowsDegradeWithoutChangingNativeQueue() throws Exception {
        for(var q:new ReproofingNotificationQueue[]{queue(1,1,0,0,0,0,0),queue(1,0,0,0,0,1,121),queue(0,0,0,0,1,0,0)}) {
            var worker=new ReproofingNotificationPollerImpl(port(Mono.just(0),Mono.just(q)),settings,new SimpleMeterRegistry(),clock);
            try {worker.start();await(()->worker.snapshot().lastSuccessAt()!=null);assertEquals("DEGRADED",worker.snapshot().state().name());assertEquals(q,worker.snapshot().queue());}
            finally {worker.stop();}
        }
    }
    @Test void concurrentStartsCannotOverlapAndFixedDelayBeginsAfterCompletion() throws Exception {
        var starts=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var live=new AtomicInteger();var maximum=new AtomicInteger();
        var dispatch=Mono.defer(()->{
            starts.add(System.nanoTime()); maximum.accumulateAndGet(live.incrementAndGet(),Math::max);
            return Mono.delay(Duration.ofMillis(350)).map(n->0).doFinally(signal->live.decrementAndGet());
        });
        var timing=new ReproofingNotificationSettings(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofSeconds(120));
        var worker=new ReproofingNotificationPollerImpl(port(dispatch,Mono.just(queue(0,0,0,0,0,0,0))),timing,registry,clock);
        var threads=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for(int n=0;n<40;n++) threads.submit(worker::start);
            await(()->starts.size()>=2);
            assertTrue(starts.get(1)-starts.get(0)>=Duration.ofMillis(1300).toNanos());
            assertEquals(1,maximum.get());
        } finally {worker.stop();threads.shutdownNow();}
    }
    @Test void dispatchAndStatusShareOneDeadlineAndLateResponseCannotOverwriteFailure() throws Exception {
        var dispatch=Mono.delay(Duration.ofMillis(600)).map(n->1);
        var status=Mono.delay(Duration.ofMillis(600)).map(n->queue(0,0,1,0,0,0,0));
        var worker=new ReproofingNotificationPollerImpl(port(dispatch,status),settings,registry,clock);
        try {
            worker.start();await(()->worker.snapshot().consecutiveFailures()==1 && !worker.snapshot().activeCycle());
            Thread.sleep(350);
            assertNull(worker.snapshot().lastSuccessAt());assertNull(worker.snapshot().queue());
            assertEquals("UNAVAILABLE",worker.snapshot().state().name());
        } finally {worker.stop();}
    }
    @Test void rejectsUnsafeSchedulingBounds() {
        assertThrows(IllegalArgumentException.class,()->new ReproofingNotificationSettings(Duration.ZERO,Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new ReproofingNotificationSettings(Duration.ofSeconds(1),Duration.ofSeconds(61),Duration.ofSeconds(90),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new ReproofingNotificationSettings(Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(120)));
    }
}
