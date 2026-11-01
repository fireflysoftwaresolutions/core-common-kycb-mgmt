package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class ReproofingSecurityConfigurationImpl {
    @Bean
    @Order(-120)
    public SecurityWebFilterChain reproofingSecurityWebFilterChain(
            ServerHttpSecurity http, Environment env) {
        String root = "/api/v1/identity-recovery/cases";
        http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(root, root + "/**"))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(
                                new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)));
        if (!env.getProperty("firefly.reproofing.enabled", Boolean.class, false))
            return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .authorizeExchange(e -> e.anyExchange().denyAll())
                    .build();
        String identity = env.getRequiredProperty("firefly.reproofing.receiver.client-id");
        String secret =
                ReproofingRemoteClientImpl.readSecret(
                        env.getRequiredProperty("firefly.reproofing.receiver.secret-file"));
        if (identity.isBlank() || identity.length() > 128 || identity.contains(":"))
            throw new IllegalStateException("Explicit receiver identity required");
        http.authenticationManager(
                        auth -> {
                            boolean match =
                                    identity.equals(auth.getName())
                                            && auth.getCredentials() instanceof String value
                                            && MessageDigest.isEqual(
                                                    secret.getBytes(StandardCharsets.UTF_8),
                                                    value.getBytes(StandardCharsets.UTF_8));
                            return match
                                    ? Mono.just(
                                            new UsernamePasswordAuthenticationToken(
                                                    identity, null, List.of()))
                                    : Mono.error(new BadCredentialsException("Workload refused"));
                        })
                .httpBasic(b ->
                        b.authenticationEntryPoint(
                                new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)));
        return http.authorizeExchange(e ->
                        e.pathMatchers(
                                        HttpMethod.POST,
                                        root,
                                        root + "/*/inspect",
                                        root + "/*/provider-session",
                                        root + "/*/confirm",
                                        root + "/*/authorizations/consume",
                                        root + "/*/authorizations/verify",
                                        root + "/*/executions")
                                .access((auth, context) ->
                                        auth.<AuthorizationResult>map(a ->
                                                        new AuthorizationDecision(
                                                                a.isAuthenticated()
                                                                        && identity.equals(
                                                                                a.getName())
                                                                        && "https"
                                                                                .equals(
                                                                                        context.getExchange()
                                                                                                .getRequest()
                                                                                                .getURI()
                                                                                                .getScheme())))
                                                .defaultIfEmpty(new AuthorizationDecision(false)))
                                .anyExchange()
                                .denyAll())
                .build();
    }
}
