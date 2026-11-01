package io.fireflysoftware.core.kycb.web.reproofing;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNotificationPort;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationQueue;
import io.fireflysoftware.core.kycb.web.reproofing.impl.*;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.*;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.context.annotation.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

class ReproofingNotificationMetricsHttpTest {
    @TempDir Path temp;
    static final String ROOT="/internal/identity-recovery/notifications";
    static final String PREFIX="firefly.reproofing.notifications.";
    @Configuration(proxyBeanMethods=false) @EnableWebFlux
    static class MetricsHttpConfiguration {}
    String fixture(String name){return name+"-fixture-"+"x".repeat(40);}
    void credential(AnnotationConfigApplicationContext context,String prefix,String name) throws Exception {
        var secret=temp.resolve(name);Files.writeString(secret,fixture(name));Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));
        var values=Map.<String,Object>of(prefix+"client-id",name,prefix+"secret-file",secret.toString());
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(name,values));
    }
    @Test void actualHttpsMetricsRequireMonitorAndPrivateRegistryNeverEntersGlobalExport() throws Exception {
        var process=new ProcessBuilder("openssl","req","-x509","-newkey","rsa:2048","-nodes","-keyout",temp.resolve("key.pem").toString(),"-out",temp.resolve("cert.pem").toString(),"-days","1","-subj","/CN=localhost","-addext","subjectAltName=DNS:localhost")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var global=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        global.counter("unrelated.fixture").increment();
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("settings",Map.of(
            "firefly.reproofing.enabled","true",PREFIX+"enabled","true","firefly.reproofing.native.base-url","https://native.example.test/realms/test/firefly-recovery/lost-credentials/rpc")));
        credential(context,"firefly.reproofing.receiver.","case");credential(context,"firefly.reproofing.native.","ceremony");
        credential(context,PREFIX,"notification");credential(context,PREFIX+"monitor.","monitor");
        context.registerBean("globalPrometheusRegistry",PrometheusMeterRegistry.class,()->global,definition->definition.setDestroyMethodName(""));
        context.registerBean("fixtureNotificationPort",ReproofingNotificationPort.class,()->new ReproofingNotificationPort(){
            public Mono<Integer> dispatchOne(){return Mono.just(0);}
            public Mono<ReproofingNotificationQueue> status(){return Mono.just(new ReproofingNotificationQueue(Instant.now(),1,0,1,0,0,0,0,0));}
        },definition->definition.setPrimary(true));
        context.register(MetricsHttpConfiguration.class,ReproofingNotificationConfigurationImpl.class,
            ReproofingSecurityConfigurationImpl.class,ReproofingNotificationSecurityConfigurationImpl.class,ReproofingNotificationController.class);
        context.refresh();
        var handler=new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build());
        var serverSsl=SslContextBuilder.forServer(temp.resolve("cert.pem").toFile(),temp.resolve("key.pem").toFile()).build();
        var clientSsl=SslContextBuilder.forClient().trustManager(temp.resolve("cert.pem").toFile()).build();
        var tls=HttpServer.create().host("localhost").port(0).secure(s->s.sslContext(serverSsl)).handle(handler).bindNow();
        var clear=HttpServer.create().host("localhost").port(0).handle(handler).bindNow();
        var transport=HttpClient.create().secure(s->s.sslContext(clientSsl));
        var client=WebTestClient.bindToServer(new ReactorClientHttpConnector(transport)).baseUrl("https://localhost:"+tls.port()).build();
        try {
            client.get().uri(ROOT+"/metrics").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class).value(body->{assertTrue(body.contains("firefly_reproofing_notifications_cycles_total"));assertFalse(body.contains("unrelated_fixture"));assertFalse(body.contains(fixture("monitor")));});
            client.get().uri(ROOT+"/metrics").exchange().expectStatus().isForbidden();
            for(String caller:new String[]{"case","ceremony","notification","ordinary"})
                client.get().uri(ROOT+"/metrics").headers(h->h.setBasicAuth(caller,fixture(caller))).exchange().expectStatus().isForbidden();
            client.get().uri(ROOT+"/metrics").headers(h->h.setBasicAuth("monitor",fixture("wrong"))).exchange().expectStatus().isForbidden();
            client.post().uri(ROOT+"/metrics").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
            client.get().uri(ROOT+"/metrics/child").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
            client.post().uri("/api/v1/identity-recovery/cases").headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
            WebTestClient.bindToServer().baseUrl("http://localhost:"+clear.port()).build().get().uri(ROOT+"/metrics")
                .headers(h->h.setBasicAuth("monitor",fixture("monitor"))).exchange().expectStatus().isForbidden();
            assertFalse(global.scrape().contains("firefly_reproofing_notifications"));assertTrue(global.scrape().contains("unrelated_fixture_total"));
            assertEquals(Set.of(global),Set.copyOf(context.getBeansOfType(MeterRegistry.class).values()));
            var privateRegistry=(PrometheusMeterRegistry)new DirectFieldAccessor(context.getBean("reproofingNotificationMetrics")).getPropertyValue("registry");
            assertNotSame(global,privateRegistry);assertFalse(Metrics.globalRegistry.getRegistries().contains(privateRegistry));
            context.close();assertTrue(privateRegistry.isClosed());assertFalse(global.isClosed());
        } finally {tls.disposeNow();clear.disposeNow();context.close();global.close();}
    }
}
