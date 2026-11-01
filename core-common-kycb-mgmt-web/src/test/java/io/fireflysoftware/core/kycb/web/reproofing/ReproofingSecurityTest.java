package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingSecurityConfigurationImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

class ReproofingSecurityTest {
    @TempDir Path temp;
    static final String ROOT = "/api/v1/identity-recovery/cases";

    @RestController("reproofingSecurityTest.Probe")
    static class ProbeImpl {
        AtomicInteger calls = new AtomicInteger();

        @RequestMapping("/api/v1/identity-recovery/cases/**")
        String hit() {
            calls.incrementAndGet();
            return "ok";
        }
    }

    @Test
    void disabledReceiverRefusesEveryFeatureRouteWithoutConfiguration() {
        var probe = new ProbeImpl();
        var chain =
                new ReproofingSecurityConfigurationImpl()
                        .reproofingSecurityWebFilterChain(
                                ServerHttpSecurity.http(), new MockEnvironment());
        var client =
                WebTestClient.bindToController(probe)
                        .webFilter(new WebFilterChainProxy(chain))
                        .build();
        for (String path :
                new String[] {ROOT, ROOT + "/id/confirm", ROOT + "/id/authorizations/consume"})
            client.post()
                    .uri("https://core.example.test" + path)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        assertEquals(0, probe.calls.get());
    }

    @Test
    void receiverRequiresExactBasicIdentityTlsAndExplicitAction() throws Exception {
        String fixture = "non-secret-fixture-" + "x".repeat(32);
        Path secret = temp.resolve("secret");
        Files.writeString(secret, fixture);
        var env =
                new MockEnvironment()
                        .withProperty("firefly.reproofing.enabled", "true")
                        .withProperty("firefly.reproofing.receiver.client-id", "domain-workload")
                        .withProperty("firefly.reproofing.receiver.secret-file", secret.toString());
        var probe = new ProbeImpl();
        var chain =
                new ReproofingSecurityConfigurationImpl()
                        .reproofingSecurityWebFilterChain(ServerHttpSecurity.http(), env);
        var client =
                WebTestClient.bindToController(probe)
                        .webFilter(new WebFilterChainProxy(chain))
                        .build();
        client.post()
                .uri("https://core.example.test" + ROOT)
                .headers(h -> h.setBasicAuth("domain-workload", fixture))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("http://core.example.test" + ROOT)
                .headers(h -> h.setBasicAuth("domain-workload", fixture))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("https://core.example.test" + ROOT)
                .headers(h -> h.setBasicAuth("native-workload", fixture))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("https://core.example.test" + ROOT)
                .headers(h -> h.setBasicAuth("domain-workload", fixture))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("https://core.example.test" + ROOT + "/id/approve")
                .headers(h -> h.setBasicAuth("domain-workload", fixture))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("https://core.example.test" + ROOT)
                .header("Authorization", "Bearer fixture")
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(1, probe.calls.get());
    }
}
