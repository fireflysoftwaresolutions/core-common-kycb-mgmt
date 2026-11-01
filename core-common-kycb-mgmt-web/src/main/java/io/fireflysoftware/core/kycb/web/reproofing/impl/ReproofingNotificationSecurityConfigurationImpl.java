package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.*;
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
public class ReproofingNotificationSecurityConfigurationImpl {
    @Bean @Order(-121)
    public SecurityWebFilterChain reproofingNotificationSecurityWebFilterChain(ServerHttpSecurity http, Environment env) {
        String root = "/internal/identity-recovery/notifications";
        http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(root, root + "/**"))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)));
        if (!env.getProperty("firefly.reproofing.enabled", Boolean.class, false)
                || !env.getProperty("firefly.reproofing.notifications.enabled", Boolean.class, false))
            return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .authorizeExchange(e -> e.anyExchange().denyAll()).build();
        var monitor = ReproofingNotificationCredentialImpl.validated(env)[1];
        http.authenticationManager(auth -> {
                    boolean match = monitor.identity.equals(auth.getName())
                            && auth.getCredentials() instanceof String value
                            && MessageDigest.isEqual(monitor.secret.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
                    return match ? Mono.just(new UsernamePasswordAuthenticationToken(monitor.identity, null, List.of()))
                            : Mono.error(new BadCredentialsException("Workload refused"));
                })
                .httpBasic(b -> b.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)));
        return http.authorizeExchange(e -> e.pathMatchers(HttpMethod.GET, root)
                        .access((auth, context) -> auth.<AuthorizationResult>map(a -> new AuthorizationDecision(
                                a.isAuthenticated() && monitor.identity.equals(a.getName())
                                        && "https".equals(context.getExchange().getRequest().getURI().getScheme())))
                                .defaultIfEmpty(new AuthorizationDecision(false)))
                        .anyExchange().denyAll()).build();
    }
}
