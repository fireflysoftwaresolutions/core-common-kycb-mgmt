package io.fireflysoftware.core.kycb.web.reproofing.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNotificationPort;
import io.fireflysoftware.core.kycb.interfaces.reproofing.ReproofingNotificationQueue;
import io.netty.channel.ChannelOption;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class ReproofingNotificationClientImpl implements ReproofingNotificationPort {
    private static final Set<String> QUEUE_FIELDS = Set.of("observedAt", "pending", "sending", "retrying", "delivered", "failed", "invalid", "due", "oldestDueDelaySeconds");
    private final WebClient client;
    private final String base, identity, secret;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ReproofingNotificationClientImpl(String base, String identity, String secret, Duration timeout) {
        try {
            URI uri = URI.create(base);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().endsWith("/firefly-recovery/lost-credentials/rpc")) throw new IllegalArgumentException();
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0)
                throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            throw new IllegalStateException("Notification HTTPS endpoint configuration unavailable");
        }
        this.base = base; this.identity = identity; this.secret = secret; this.timeout = timeout;
        var transport = HttpClient.create().followRedirect(false).responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeout.toMillis()));
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32768)).build();
    }
    @Override public Mono<Integer> dispatchOne() {
        return call("/dispatch", Map.of("limit", 1)).map(node -> {
            fields(node, Set.of("processed"));
            long count = count(node, "processed");
            if (count > 1) throw new IllegalArgumentException("Invalid notification response");
            return (int) count;
        });
    }
    @Override public Mono<ReproofingNotificationQueue> status() {
        return call("/notification-status", Map.of()).map(node -> {
            fields(node, QUEUE_FIELDS);
            try {
                if (!node.get("observedAt").isTextual()) throw new IllegalArgumentException();
                return new ReproofingNotificationQueue(Instant.parse(node.get("observedAt").textValue()),
                        count(node,"pending"), count(node,"sending"), count(node,"retrying"), count(node,"delivered"),
                        count(node,"failed"), count(node,"invalid"), count(node,"due"), count(node,"oldestDueDelaySeconds"));
            } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid notification response"); }
        });
    }
    private Mono<JsonNode> call(String route, Object body) {
        return client.post().uri(base + route)
                .headers(h -> h.setBasicAuth(identity, secret, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() != 200 || !response.headers().contentType()
                            .map(MediaType.APPLICATION_JSON::isCompatibleWith).orElse(false))
                        return response.releaseBody().then(Mono.error(new IllegalStateException("Notification dependency unavailable")));
                    return response.bodyToMono(String.class);
                })
                .timeout(timeout)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid notification response")))
                .onErrorMap(error -> new IllegalStateException("Notification dependency unavailable"))
                .map(bodyText -> {
                    try { return json.readTree(bodyText); }
                    catch (Exception error) { throw new IllegalArgumentException("Invalid notification response"); }
                });
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size())
            throw new IllegalArgumentException("Invalid notification response");
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(expected)) throw new IllegalArgumentException("Invalid notification response");
    }
    private static long count(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("Invalid notification response");
        return value.longValue();
    }
}
