package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Component
@Order(-110)
public final class ReproofingBodyLimitFilterImpl implements WebFilter {
    private final String cacheHeader;

    public ReproofingBodyLimitFilterImpl() {
        this("X-Idempotency-Key");
    }

    @Autowired
    public ReproofingBodyLimitFilterImpl(Environment environment) {
        this(environment.getProperty("firefly.web.idempotency.header-name", "X-Idempotency-Key"));
    }

    private ReproofingBodyLimitFilterImpl(String cacheHeader) {
        if (!cacheHeader.matches("(?i)X-[A-Z0-9-]{1,125}")) {
            throw new IllegalStateException("A separate generic cache header is required");
        }
        this.cacheHeader = cacheHeader;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.equals("/api/v1/identity-recovery/cases")
                && !path.startsWith("/api/v1/identity-recovery/cases/")) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getHeaders().getContentLength() > 32768) {
            return refuse(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
        }
        var isolated = exchange.mutate().request(request ->
                request.headers(headers -> headers.remove(cacheHeader))).build();
        return DataBufferUtils.join(isolated.getRequest().getBody(), 32768)
                .timeout(Duration.ofSeconds(5))
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    var request = new ServerHttpRequestDecorator(isolated.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> Flux.just(
                                    isolated.getResponse().bufferFactory().wrap(bytes)));
                        }
                    };
                    return chain.filter(isolated.mutate().request(request).build());
                })
                .onErrorResume(DataBufferLimitException.class,
                        error -> refuse(isolated, HttpStatus.PAYLOAD_TOO_LARGE))
                .onErrorResume(TimeoutException.class,
                        error -> refuse(isolated, HttpStatus.REQUEST_TIMEOUT));
    }

    private Mono<Void> refuse(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
