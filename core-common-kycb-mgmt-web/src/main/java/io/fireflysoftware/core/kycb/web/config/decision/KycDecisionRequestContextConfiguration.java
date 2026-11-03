/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.web.config.decision;

import io.fireflysoftware.core.kycb.core.services.decision.party.KycCallerToken;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.WebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class KycDecisionRequestContextConfiguration {
    static final String ROUTE = "/api/v1/tenants/*/parties/*/kyc-cases/*/manual-decisions/**";
    @Bean
    WebFilter kycDecisionCallerContext() {
        var matcher = ServerWebExchangeMatchers.pathMatchers(ROUTE);
        return (exchange, chain) -> matcher.matches(exchange).flatMap(match -> {
            if (!match.isMatch()) return chain.filter(exchange);
            exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
            var headers = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
            if (headers == null || headers.size() != 1 || !headers.getFirst().startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);return exchange.getResponse().setComplete();
            }
            String value = headers.getFirst().substring(7);
            if (value.isBlank() || value.length() > 16384 || value.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);return exchange.getResponse().setComplete();
            }
            // Identity is validated by the native receiver and independently before subject exchange.
            return chain.filter(exchange).contextWrite(context -> context.put(KycCallerToken.class, new KycCallerToken(value)));
        });
    }
}
