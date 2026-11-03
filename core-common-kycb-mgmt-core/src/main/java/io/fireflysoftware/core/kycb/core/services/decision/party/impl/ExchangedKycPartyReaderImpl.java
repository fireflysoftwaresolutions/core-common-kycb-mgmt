/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision.party.impl;

import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.Permit;
import io.fireflysoftware.core.kycb.core.services.decision.party.*;
import io.fireflysoftware.core.customer.sdk.api.PartiesApi;
import io.fireflysoftware.core.customer.sdk.invoker.ApiClient;
import io.fireflysoftware.core.customer.sdk.invoker.auth.HttpBearerAuth;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.Hints;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.*;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Native subject exchange preserving the signed human/staff identity, then the complete official Customer SDK. */
public final class ExchangedKycPartyReaderImpl implements KycPartyReader {
    private static final Set<String> SCOPES=Set.of("customer:parties:read");
    public record Settings(URI endpoint,String issuer,String kycAudience,String customerAudience,String registrationId,String clientId,boolean allowLoopbackHttp) {
        public Settings {secure(endpoint,allowLoopbackHttp);secure(URI.create(issuer),allowLoopbackHttp);for(String value:List.of(kycAudience,customerAudience,registrationId,clientId))text(value);}
        @Override public String toString(){return "KycCustomerDelegationSettings";}
    }
    private final Settings settings;
    private final CurrentKycDecisionAuthorityImpl authority;
    private final SecurityContextPort security;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder incoming,outgoing;
    private final WebClientReactiveTokenExchangeTokenResponseClient exchange;
    private final WebClient transport;
    private final Clock clock;
    public ExchangedKycPartyReaderImpl(Settings settings,CurrentKycDecisionAuthorityImpl authority,SecurityContextPort security,
            ReactiveClientRegistrationRepository registrations,ReactiveJwtDecoder incoming,ReactiveJwtDecoder outgoing,Clock clock){
        this(settings,authority,security,registrations,incoming,outgoing,clock,new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build()));
    }
    ExchangedKycPartyReaderImpl(Settings settings,CurrentKycDecisionAuthorityImpl authority,SecurityContextPort security,
            ReactiveClientRegistrationRepository registrations,ReactiveJwtDecoder incoming,ReactiveJwtDecoder outgoing,Clock clock,ClientHttpConnector connector){
        this.settings=Objects.requireNonNull(settings);this.authority=Objects.requireNonNull(authority);this.security=Objects.requireNonNull(security);this.registrations=Objects.requireNonNull(registrations);this.incoming=Objects.requireNonNull(incoming);this.outgoing=Objects.requireNonNull(outgoing);this.clock=Objects.requireNonNull(clock);
        transport=ApiClient.buildWebClientBuilder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(65536);c.defaultCodecs().enableLoggingRequestDetails(false);}).build();
        var tokens=WebClient.builder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(16384);c.defaultCodecs().enableLoggingRequestDetails(false);})
            .filter((request,next)->next.exchange(request).flatMap(reply->reply.statusCode().is2xxSuccessful()?Mono.just(reply):reply.releaseBody().then(Mono.error(unavailable())))).build();
        exchange=new WebClientReactiveTokenExchangeTokenResponseClient();exchange.setWebClient(tokens);exchange.addParametersConverter(request->{var values=new LinkedMultiValueMap<String,String>();values.set("audience",settings.customerAudience());return values;});
        var converter=new DefaultMapOAuth2AccessTokenResponseConverter();exchange.setBodyExtractor((message,context)->BodyExtractors.toMono(new ParameterizedTypeReference<Map<String,Object>>(){}).extract(message,new BodyExtractor.Context(){
            public List<HttpMessageReader<?>> messageReaders(){return context.messageReaders();}public Optional<ServerHttpResponse> serverResponse(){return context.serverResponse();}
            public Map<String,Object> hints(){var hints=new HashMap<String,Object>(context.hints());hints.put(Hints.SUPPRESS_LOGGING_HINT,true);return Map.copyOf(hints);}
        }).map(values->{if(values.containsKey("error")||!"urn:ietf:params:oauth:token-type:access_token".equals(values.get("issued_token_type")))throw denied();return converter.convert(values);}));
    }
    public Mono<Observation> observe(Permit permit){
        return Mono.deferContextual(context->{
            if(permit==null)throw denied();permit.remaining();KycCallerToken bearer=context.getOrDefault(KycCallerToken.class,null);if(bearer==null)throw denied();
            return authority.requireCurrent(permit).then(Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(denied())))
                .flatMap(principal->incoming.decode(bearer.value()).switchIfEmpty(Mono.error(denied())).flatMap(original->{
                    original(original,principal,permit);
                    return registrations.findByRegistrationId(settings.registrationId()).switchIfEmpty(Mono.error(unavailable())).flatMap(registration->{
                        registration(registration);var scoped=ClientRegistration.withClientRegistration(registration).scope(SCOPES).build();
                        return authority.requireCurrent(permit).then(exchange.getTokenResponse(new TokenExchangeGrantRequest(scoped,original,null)))
                            .timeout(permit.remaining()).switchIfEmpty(Mono.error(unavailable())).flatMap(response->{
                                var token=response.getAccessToken();if(token==null||!OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType())||!SCOPES.equals(token.getScopes())||token.getExpiresAt()==null||!clock.instant().isBefore(token.getExpiresAt()))throw denied();
                                return outgoing.decode(token.getTokenValue()).switchIfEmpty(Mono.error(denied())).flatMap(target->{
                                    target(target,original,principal,permit);if(!target.getTokenValue().equals(token.getTokenValue()))throw denied();
                                    var client=new ApiClient(transport).setBasePath(settings.endpoint().toASCIIString().replaceFirst("/$",""));
                                    if(!(client.getAuthentication("customerBearer") instanceof HttpBearerAuth auth))throw unavailable();auth.setBearerToken(target.getTokenValue());
                                    return authority.requireCurrent(permit).then(new PartiesApi(client).getPartyByIdWithHttpInfo(permit.scope().partyId(),null)).timeout(permit.remaining()).switchIfEmpty(Mono.error(unavailable()))
                                        .flatMap(reply->{
                                            var party=reply.getBody();if(reply.getStatusCode().value()!=200||party==null||!permit.scope().partyId().equals(party.getPartyId())||!permit.scope().tenantId().equals(party.getTenantId())||party.getPartyKind()==null)throw denied();
                                            return authority.requireCurrent(permit).then(Mono.defer(security::currentPrincipal).filter(principal::equals).switchIfEmpty(Mono.error(denied())))
                                                .map(again->{target(target,original,again,permit);permit.remaining();Instant now=clock.instant(),until=Collections.min(List.of(permit.until(),target.getExpiresAt(),token.getExpiresAt()));return new Observation(party.getTenantId(),party.getPartyId(),party.getPartyKind().getValue(),now,until);});
                                        });
                                });
                            });
                    });
                })).timeout(permit.remaining());
        }).onErrorMap(error->error instanceof ResponseStatusException?error:error instanceof WebClientResponseException response&&Set.of(401,403,404).contains(response.getStatusCode().value())?denied():unavailable());
    }
    private void original(Jwt original,SecurityPrincipal principal,Permit permit){
        valid(original,permit);if(!List.of(settings.kycAudience()).equals(original.getAudience())||!principal.claims().equals(original.getClaims())||!principal.subject().equals(original.getSubject())||!settings.issuer().equals(principal.issuer())||!principal.tenantId().equals(permit.scope().tenantId().toString())||!principal.scopes().containsAll(SCOPES)||!scopes(original).containsAll(SCOPES))throw denied();
    }
    private void target(Jwt target,Jwt original,SecurityPrincipal principal,Permit permit){
        original(original,principal,permit);valid(target,permit);
        if(!List.of(settings.customerAudience()).equals(target.getAudience())||!settings.clientId().equals(claim(target,"client_id"))||!SCOPES.equals(scopes(target))||!original.getSubject().equals(target.getSubject())||target.getExpiresAt().isAfter(original.getExpiresAt())||target.getNotBefore().isBefore(original.getNotBefore())||!claim(original,"principal_kind").equals(claim(target,"principal_kind")))throw denied();
        for(String key:"STAFF".equals(claim(original,"principal_kind"))?List.of("staff_actor_id","staff_realm"):List.of("party_id"))if(!claim(original,key).equals(claim(target,key)))throw denied();
    }
    private void valid(Jwt jwt,Permit permit){
        var now=clock.instant();String kind=claim(jwt,"principal_kind");
        if(!settings.issuer().equals(jwt.getIssuer()==null?null:jwt.getIssuer().toString())||jwt.getSubject()==null||jwt.getSubject().isBlank()||!permit.scope().tenantId().toString().equals(claim(jwt,"tenant_id"))||!Set.of("STAFF","HUMAN").contains(kind)||!(jwt.getClaims().get("exp") instanceof Instant)||!(jwt.getClaims().get("nbf") instanceof Instant)||jwt.getExpiresAt()==null||jwt.getNotBefore()==null||!now.isBefore(jwt.getExpiresAt())||now.isBefore(jwt.getNotBefore())||!jwt.getNotBefore().isBefore(jwt.getExpiresAt()))throw denied();
        if("HUMAN".equals(kind)&&!permit.scope().partyId().toString().equals(claim(jwt,"party_id")))throw denied();permit.remaining();
    }
    private void registration(ClientRegistration value){secure(URI.create(value.getProviderDetails().getTokenUri()),settings.allowLoopbackHttp());if(!settings.registrationId().equals(value.getRegistrationId())||!AuthorizationGrantType.TOKEN_EXCHANGE.equals(value.getAuthorizationGrantType())||!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(value.getClientAuthenticationMethod())||!settings.clientId().equals(value.getClientId())||!settings.issuer().equals(value.getProviderDetails().getIssuerUri())||value.getClientSecret()==null||value.getClientSecret().isBlank()||!value.getScopes().containsAll(SCOPES))throw denied();}
    private static Set<String> scopes(Jwt jwt){Object value=jwt.getClaims().get("scope");if(!(value instanceof String text)||text.length()>4096||text.chars().anyMatch(c->c<0x20||c>0x7e)||jwt.getClaims().containsKey("scp"))throw denied();var values=Set.copyOf(Arrays.asList(text.split(" ",-1)));if(values.contains(""))throw denied();return values;}
    private static String claim(Jwt jwt,String name){Object value=jwt.getClaims().get(name);if(!(value instanceof String text))throw denied();text(text);return text;}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>512||value.codePoints().anyMatch(Character::isISOControl))throw denied();}
    private static void secure(URI uri,boolean loopback){if(uri==null||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!("https".equals(uri.getScheme())||loopback&&"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost())))throw new IllegalArgumentException("EXPLICIT_SECURE_KYC_CUSTOMER_ENDPOINT_REQUIRED");}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Current original Customer party authority required");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Current Customer owner observation unavailable");}
    @Override public String toString(){return "ExchangedKycPartyReader[redacted]";}
}
