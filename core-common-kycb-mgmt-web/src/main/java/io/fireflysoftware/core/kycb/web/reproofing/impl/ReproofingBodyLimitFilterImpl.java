package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Order(-110)
public final class ReproofingBodyLimitFilterImpl implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.equals("/api/v1/identity-recovery/cases")
                && !path.startsWith("/api/v1/identity-recovery/cases/"))
            return chain.filter(exchange);
        if (exchange.getRequest().getHeaders().getContentLength() > 32768) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), 32768)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    var request =
                            new ServerHttpRequestDecorator(exchange.getRequest()) {
                                @Override
                                public Flux<DataBuffer> getBody() {
                                    return Flux.defer(() ->
                                            Flux.just(
                                                    exchange.getResponse()
                                                            .bufferFactory()
                                                            .wrap(bytes)));
                                }
                            };
                    return chain.filter(exchange.mutate().request(request).build());
                })
                .onErrorResume(
                        DataBufferLimitException.class,
                        error -> {
                            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                            return exchange.getResponse().setComplete();
                        });
    }
}
