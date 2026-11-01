package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingBodyLimitFilterImpl;

import org.fireflyframework.web.idempotency.cache.IdempotencyCache;
import org.fireflyframework.web.idempotency.config.IdempotencyProperties;
import org.fireflyframework.web.idempotency.filter.IdempotencyWebFilter;
import org.fireflyframework.web.idempotency.model.CachedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

class ReproofingCacheIsolationTest {
    @RestController
    static class Probe {
        int calls;

        @PostMapping("/api/v1/identity-recovery/cases")
        String dispatch() {
            return Integer.toString(++calls);
        }
    }

    @Test
    void genericCacheHeaderNeverBypassesFreshRecoveryDispatch() {
        var probe = new Probe();
        IdempotencyCache cache =
                new IdempotencyCache() {
                    public Mono<CachedResponse> get(String key) {
                        return Mono.error(
                                new AssertionError(
                                        "Recovery must never read generic cached authority"));
                    }

                    public Mono<Void> put(String key, CachedResponse value) {
                        return Mono.error(
                                new AssertionError("Recovery must never cache authority"));
                    }
                };
        var client =
                WebTestClient.bindToController(probe)
                        .webFilter(
                                new ReproofingBodyLimitFilterImpl(),
                                (exchange, chain) -> {
                                    assertNull(exchange.getRequest().getHeaders().getFirst("X-Idempotency-Key"), "Recovery must remove generic cache authority before dispatch");
                                    return chain.filter(exchange);
                                },
                                new IdempotencyWebFilter(new IdempotencyProperties(), cache))
                        .build();
        for (int i = 0; i < 2; i++)
            client.post()
                    .uri("/api/v1/identity-recovery/cases")
                    .header("X-Idempotency-Key", "same-key")
                    .exchange()
                    .expectStatus()
                    .isOk();
        assertEquals(2, probe.calls);
    }
}
