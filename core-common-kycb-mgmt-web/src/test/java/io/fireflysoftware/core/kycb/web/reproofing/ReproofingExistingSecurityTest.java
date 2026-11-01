package io.fireflysoftware.core.kycb.web.reproofing;

import io.fireflysoftware.core.kycb.web.reproofing.impl.ReproofingSecurityConfigurationImpl;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class ReproofingExistingSecurityTest {
    private ReactiveWebApplicationContextRunner runner(){
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ResourceServerAutoConfiguration.class))
                .withUserConfiguration(ProductConfiguration.class)
                .withPropertyValues("firefly.security.resource-server.enabled=true");
    }
    @Configuration(proxyBeanMethods=false)
    @EnableWebFlux
    @ComponentScan(basePackageClasses=ReproofingSecurityConfigurationImpl.class)
    static class ProductConfiguration {
        @Bean Probe probe(){return new Probe();}
        @Bean ReactiveJwtDecoder fixtureDecoder(){return token->Mono.error(new BadJwtException("Fixture refuses bearer"));}
    }
    @RestController static class Probe {
        @GetMapping("/api/existing") String existing(){return "unrelated";}
    }
    @Test void optionalConfiguredFrameworkPolicyCannotSilentlyBackOff(){
        runner().run(context->assertThat(context).hasFailed());
    }
    @Configuration(proxyBeanMethods=false)
    static class ExplicitExistingPolicy {
        @Bean(name="fireflySecurityWebFilterChain")
        SecurityWebFilterChain existing(ServerHttpSecurity http,ReactiveJwtDecoder decoder,
                Converter<Jwt,Mono<AbstractAuthenticationToken>> converter,
                PolicyDecisionPort policy,ResourceServerProperties properties){
            return new ResourceServerAutoConfiguration().fireflySecurityWebFilterChain(http,decoder,converter,policy,properties);
        }
    }
    @Test void separatelyConfiguredOriginalFrameworkChainStillProtectsUnrelatedRoutes(){
        runner().withUserConfiguration(ExplicitExistingPolicy.class).run(context->{
            assertThat(context).hasNotFailed();
            var client=WebTestClient.bindToApplicationContext(context).build();
            client.get().uri("/api/existing").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/existing").header("Authorization","Bearer wrong-fixture").exchange().expectStatus().isUnauthorized();
            client.post().uri("https://core.example.test/api/v1/identity-recovery/cases").exchange().expectStatus().isForbidden();
        });
    }
}
