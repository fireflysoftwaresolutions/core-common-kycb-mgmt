package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.web.reproofing.impl.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

class ReproofingNotificationConfigurationTest {
    @TempDir Path temp;
    static final String PREFIX="firefly.reproofing.notifications.";
    static final String ROOT="/internal/identity-recovery/notifications";
    String fixture(String name) {return name+"-fixture-"+"x".repeat(40);}
    String secret(String name) throws Exception {
        var p=temp.resolve(name);Files.writeString(p,fixture(name));Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p.toString();
    }
    MockEnvironment env() throws Exception {
        return new MockEnvironment().withProperty("firefly.reproofing.enabled","true").withProperty(PREFIX+"enabled","true")
            .withProperty("firefly.reproofing.receiver.client-id","case").withProperty("firefly.reproofing.receiver.secret-file",secret("case"))
            .withProperty("firefly.reproofing.native.client-id","ceremony").withProperty("firefly.reproofing.native.secret-file",secret("ceremony"))
            .withProperty("firefly.reproofing.native.base-url","https://native.example.test/realms/test/firefly-recovery/lost-credentials/rpc")
            .withProperty(PREFIX+"client-id","notification").withProperty(PREFIX+"secret-file",secret("notification"))
            .withProperty(PREFIX+"monitor.client-id","monitor").withProperty(PREFIX+"monitor.secret-file",secret("monitor"));
    }
    @RestController static class Probe {
        @RequestMapping("/internal/identity-recovery/notifications/**") String hit(){return "ok";}
    }
    WebTestClient client(MockEnvironment env) {
        var chain=new ReproofingNotificationSecurityConfigurationImpl().reproofingNotificationSecurityWebFilterChain(ServerHttpSecurity.http(),env);
        return WebTestClient.bindToController(new Probe()).webFilter(new WebFilterChainProxy(chain, new ReproofingSecurityConfigurationImpl().reproofingSecurityWebFilterChain(ServerHttpSecurity.http(),env))).build();
    }
    @Test void disabledLifecycleNeedsNoCredentialsAndClosesMonitorIncludingSubpaths() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(ReproofingNotificationConfigurationImpl.class)
            .withBean(SimpleMeterRegistry.class,SimpleMeterRegistry::new)
            .run(c->{assertFalse(c.containsBean("reproofingNotificationPoller"));assertFalse(c.containsBean("reproofingNotificationPort"));});
        var client=client(new MockEnvironment());
        for(String path:new String[]{ROOT,ROOT+"/child"}) client.get().uri("https://core.example.test"+path).exchange().expectStatus().isForbidden();
    }
    @Test void globalFeatureFlagStillDisablesAnExplicitNotificationFlag() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(ReproofingNotificationConfigurationImpl.class)
            .withPropertyValues(PREFIX+"enabled=true", "firefly.reproofing.enabled=false")
            .run(c->assertFalse(c.containsBean("reproofingNotificationPoller")));
        client(new MockEnvironment().withProperty(PREFIX+"enabled","true")).get()
            .uri("https://core.example.test"+ROOT).exchange().expectStatus().isForbidden();
    }
    @Test void missingOrMixedCredentialAndUnsafeFilesFailWithoutDisclosingPaths() throws Exception {
        var configuration=new ReproofingNotificationConfigurationImpl();
        var mixed=env(); mixed.setProperty(PREFIX+"client-id","case");
        assertThrows(IllegalStateException.class,()->configuration.reproofingNotificationPort(mixed));
        var reused=env(); reused.setProperty(PREFIX+"monitor.secret-file",reused.getProperty(PREFIX+"secret-file")); assertThrows(IllegalStateException.class,()->configuration.reproofingNotificationPort(reused));
        var p=Path.of(secret("unsafe"));Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r--r--"));
        var unsafe=env();unsafe.setProperty(PREFIX+"secret-file",p.toString());
        var error=assertThrows(IllegalStateException.class,()->configuration.reproofingNotificationPort(unsafe));assertFalse(error.getMessage().contains(p.toString()));
        var link=temp.resolve("link");Files.createSymbolicLink(link,Path.of(secret("target")));
        var linked=env();linked.setProperty(PREFIX+"secret-file",link.toString());assertThrows(IllegalStateException.class,()->configuration.reproofingNotificationPort(linked));
        var missing=env();missing.setProperty(PREFIX+"monitor.secret-file",temp.resolve("missing").toString());assertThrows(IllegalStateException.class,()->configuration.reproofingNotificationPort(missing));
    }
    @Test void monitorRequiresSeparateExactIdentityHttpsAndGetOnly() throws Exception {
        var client=client(env());
        client.get().uri("https://core.example.test"+ROOT).headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isOk();
        for(String identity:new String[]{"case","ceremony","notification","ordinary"}) client.get().uri("https://core.example.test"+ROOT).headers(h->h.setBasicAuth(identity,fixture(identity))).exchange().expectStatus().isForbidden();
        client.get().uri("http://core.example.test"+ROOT).headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
        client.post().uri("https://core.example.test"+ROOT).headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
        client.get().uri("https://core.example.test"+ROOT+"/child").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
        client.get().uri("https://core.example.test"+ROOT).exchange().expectStatus().isForbidden();
        client.post().uri("https://core.example.test/api/v1/identity-recovery/cases").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
    }
}
