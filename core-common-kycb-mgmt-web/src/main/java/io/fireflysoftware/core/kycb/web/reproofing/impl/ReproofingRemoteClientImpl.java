package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ReproofingRemoteClientImpl {
    private final WebClient client;
    private final String base,identity,secret;
    public ReproofingRemoteClientImpl(String base,String identity,String secretFile){
        URI uri=URI.create(base);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || base.endsWith("/"))throw new IllegalStateException("Explicit HTTPS endpoint required");
        if(identity==null || identity.isBlank() || identity.length()>128 || identity.contains(":"))throw new IllegalStateException("Explicit workload identity required");
        this.base=base;this.identity=identity;this.secret=readSecret(secretFile);
        var transport=HttpClient.create().followRedirect(false).responseTimeout(Duration.ofSeconds(5)).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,5000);
        client=WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport)).codecs(c->c.defaultCodecs().maxInMemorySize(32768)).build();
    }
    static String readSecret(String file){
        try {Path path=Path.of(file);if(!Files.isRegularFile(path) || Files.size(path)>4096)throw new IllegalStateException();String value=Files.readString(path,StandardCharsets.UTF_8).strip();
            if(value.length()<32 || value.length()>2048 || value.chars().anyMatch(Character::isISOControl))throw new IllegalStateException();return value;
        }catch(Exception error){throw new IllegalStateException("Mounted workload credential unavailable");}
    }
    public <T> Mono<T> call(String route,Object body,Class<T> response){
        return client.post().uri(base+route).headers(h->h.setBasicAuth(identity,secret,StandardCharsets.UTF_8))
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchangeToMono(r->{
                if(r.statusCode().value()!=200 || !r.headers().contentType().map(MediaType.APPLICATION_JSON::isCompatibleWith).orElse(false))
                    return r.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recovery dependency unavailable")));
                return r.bodyToMono(response);
            }).timeout(Duration.ofSeconds(5)).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recovery dependency unavailable")))
            .onErrorMap(e->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recovery dependency unavailable"));
    }
}
