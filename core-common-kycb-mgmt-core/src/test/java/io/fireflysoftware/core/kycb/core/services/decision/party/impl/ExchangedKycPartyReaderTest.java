package io.fireflysoftware.core.kycb.core.services.decision.party.impl;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.*;
import io.fireflysoftware.core.kycb.core.services.decision.party.*;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.fireflyframework.security.rs.*;
import org.fireflyframework.security.webflux.authentication.PrincipalSupport;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import reactor.core.publisher.Mono;

/** Genuine signed decoder/projection, OAuth exchange and full SDK on loopback; controlled Customer response, not owner Main. */
class ExchangedKycPartyReaderTest {
    static final Scope SCOPE=new Scope(new UUID(0,1),new UUID(0,2),new UUID(0,3));
    static final UUID ACTION=new UUID(0,4),STAFF=new UUID(0,5);
    static final String READ="customer:parties:read",CURRENT="kyc:manual-decisions:current";
    static final ObjectMapper JSON=new ObjectMapper();
    final class Fixture implements AutoCloseable {
        final RSAKey key=new RSAKeyGenerator(2048).keyID("controlled-kyc-party").generate();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger exchanges=new AtomicInteger(),reads=new AtomicInteger();
        final AtomicLong nanos=new AtomicLong();final AtomicBoolean policy=new AtomicBoolean(true);
        final AtomicReference<SecurityPrincipal> principal=new AtomicReference<>();
        final AtomicReference<String> targetToken=new AtomicReference<>();
        final AtomicReference<Throwable> handlerError=new AtomicReference<>();
        final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        final String kind;String original;
        volatile UnaryOperator<Map<String,Object>> mutate=claims->claims;
        volatile UUID returnedTenant=SCOPE.tenantId(),returnedParty=SCOPE.partyId();
        volatile int returnedStatus=200;
        volatile Runnable afterExchange=()->{},afterRead=()->{};
        Fixture(String kind)throws Exception{
            this.kind=kind;
            server.createContext("/jwks",exchange->{try(exchange){var bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}});
            server.createContext("/token",exchange->{try(exchange){try{
                exchanges.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());assertEquals("Basic "+Base64.getEncoder().encodeToString("kyc-customer:controlled-test-only".getBytes(StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                var fields=new LinkedHashMap<String,String>();byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);
                for(var part:new String(raw,StandardCharsets.UTF_8).split("&")){var pair=part.split("=",2);assertNull(fields.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8)));}
                assertEquals("urn:ietf:params:oauth:grant-type:token-exchange",fields.get("grant_type"));assertEquals(original,fields.get("subject_token"));assertEquals("core-customer",fields.get("audience"));assertEquals(READ,fields.get("scope"));
                var claims=claims("core-customer","kyc-customer",READ);claims.put("exp",Date.from(now.plusSeconds(90)));String target=sign(mutate.apply(claims));targetToken.set(target);afterExchange.run();
                byte[] bytes=JSON.writeValueAsBytes(Map.of("access_token",target,"token_type","Bearer","issued_token_type","urn:ietf:params:oauth:token-type:access_token","expires_in",30,"scope",READ));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){handlerError.set(error);}}});
            server.createContext("/api/v1/parties/",exchange->{try(exchange){try{
                reads.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());assertEquals("/api/v1/parties/"+SCOPE.partyId(),exchange.getRequestURI().getPath());assertNull(exchange.getRequestURI().getQuery());assertEquals(0,exchange.getRequestBody().readAllBytes().length);assertNull(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));assertEquals("Bearer "+targetToken.get(),exchange.getRequestHeaders().getFirst("Authorization"));assertNotEquals("Bearer "+original,exchange.getRequestHeaders().getFirst("Authorization"));
                afterRead.run();byte[] bytes=JSON.writeValueAsBytes(Map.of("partyId",returnedParty,"tenantId",returnedTenant,"partyKind","INDIVIDUAL"));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(returnedStatus,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){handlerError.set(error);}}});
            server.start();original=sign(claims("core-kyc","staff-console",CURRENT+" "+READ));project();
        }
        URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        String issuer(){return base()+"/issuer";}
        Map<String,Object> claims(String audience,String client,String scopes){var v=new HashMap<String,Object>();v.put("iss",issuer());v.put("sub","original-subject");v.put("aud",List.of(audience));v.put("tenant_id",SCOPE.tenantId().toString());v.put("client_id",client);v.put("scope",scopes);v.put("principal_kind",kind);v.put("staff_actor_id",STAFF.toString());v.put("staff_realm","institution-staff");v.put("party_id",SCOPE.partyId().toString());v.put("nbf",Date.from(now.minusSeconds(1)));v.put("exp",Date.from(now.plusSeconds(120)));return v;}
        String sign(Map<String,Object> values)throws Exception{var builder=new JWTClaimsSet.Builder();for(var e:values.entrySet()){if(e.getKey().equals("exp"))builder.expirationTime((Date)e.getValue());else if(e.getKey().equals("nbf"))builder.notBeforeTime((Date)e.getValue());else builder.claim(e.getKey(),e.getValue());}var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),builder.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();}
        ResourceServerProperties trust(String audience){var p=new ResourceServerProperties();p.setIssuer(issuer());p.setAudiences(List.of(audience));p.setJwkSetUri(base()+"/jwks");p.setTenantClaimPaths(List.of("tenant_id"));p.setScopeClaimPaths(List.of("scope"));p.setTenantRequired(true);return p;}
        org.springframework.security.oauth2.jwt.ReactiveJwtDecoder decoder(String audience){return new ResourceServerAutoConfiguration().fireflyReactiveJwtDecoder(new org.fireflyframework.security.core.key.InMemoryKeyManagementAdapter(),trust(audience));}
        void project(){var p=trust("core-kyc");var config=new ResourceServerAutoConfiguration();var jwt=decoder("core-kyc").decode(original).block(Duration.ofSeconds(5));var factory=config.fireflyPrincipalFactory(config.fireflyAuthorityMappingPort(p));principal.set(PrincipalSupport.extract(config.fireflyJwtAuthenticationConverter(factory,p).convert(jwt).block(Duration.ofSeconds(5))));}
        CurrentKycDecisionAuthorityImpl authority(){var clients=new EnumMap<Operation,Set<String>>(Operation.class);for(var op:Operation.values())clients.put(op,Set.of("staff-console"));return new CurrentKycDecisionAuthorityImpl(()->Mono.just(principal.get()),(p,a,r,c)->Mono.just(new PolicyEvaluation(policy.get()?Decision.permit():Decision.deny("revoked"),Optional.of(new PolicyEvaluationProvenance("policy-1","decision-1")))),new Profile(issuer(),"core-kyc",clients),Clock.systemUTC(),nanos::get);}
        Mono<KycPartyReader.Observation> read(boolean bearer){
            var authority=authority();var registration=ClientRegistration.withRegistrationId("customer").clientId("kyc-customer").clientSecret("controlled-test-only").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE).tokenUri(base()+"/token").issuerUri(issuer()).scope(READ).build();
            var reader=new ExchangedKycPartyReaderImpl(new ExchangedKycPartyReaderImpl.Settings(base(),issuer(),"core-kyc","core-customer","customer","kyc-customer",true),authority,()->Mono.just(principal.get()),new InMemoryReactiveClientRegistrationRepository(registration),decoder("core-kyc"),decoder("core-customer"),Clock.systemUTC());
            var read=authority.authorize(SCOPE,Operation.CURRENT,ACTION).flatMap(reader::observe);return bearer?read.contextWrite(c->c.put(KycCallerToken.class,new KycCallerToken(original))):read;
        }
        KycPartyReader.Observation read(){var value=read(true).block(Duration.ofSeconds(6));assertNull(handlerError.get());return value;}
        @Override public void close(){server.stop(0);}
    }
    @Test void actualNativeStaffExchangeUsesNamedBearerAndOriginalFullSdkWithoutEligibilityInference()throws Exception{try(var f=new Fixture("STAFF")){var value=f.read();assertEquals(SCOPE.partyId(),value.partyId());assertEquals(SCOPE.tenantId(),value.tenantId());assertEquals("INDIVIDUAL",value.partyKind());assertEquals(1,f.exchanges.get());assertEquals(1,f.reads.get());}}
    @Test void exactHumanPartySurvivesRealExchange()throws Exception{try(var f=new Fixture("HUMAN")){assertEquals(SCOPE.partyId(),f.read().partyId());assertEquals(1,f.reads.get());}}
    @Test void absentBearerAndChangedNativeCallerNeverReachExchange()throws Exception{try(var f=new Fixture("STAFF")){assertThrows(RuntimeException.class,()->f.read(false).block());assertEquals(0,f.exchanges.get());f.principal.set(f.principal.get().toBuilder().subject("changed").build());assertThrows(RuntimeException.class,f::read);assertEquals(0,f.exchanges.get());}}
    @Test void wrongTargetStaffIdentityAndRealmRefuseBeforeCustomer()throws Exception{for(String field:List.of("staff_actor_id","staff_realm"))try(var f=new Fixture("STAFF")){f.mutate=c->{c.put(field,field.equals("staff_actor_id")?new UUID(0,99).toString():"another-realm");return c;};assertThrows(RuntimeException.class,f::read);assertEquals(0,f.reads.get());}}
    @Test void wrongTargetPartyAudienceClientTenantAndExpandedScopesRefuse()throws Exception{for(String field:List.of("party_id","aud","client_id","tenant_id","scope"))try(var f=new Fixture("HUMAN")){f.mutate=c->{c.put(field,field.equals("aud")?List.of("another-owner"):field.equals("scope")?READ+" customer:parties:update":"another-value");return c;};assertThrows(RuntimeException.class,f::read);assertEquals(0,f.reads.get());}}
    @Test void expiryExtensionAndOriginalBudgetExhaustionRefuseBeforeCustomer()throws Exception{try(var f=new Fixture("STAFF")){f.mutate=c->{c.put("exp",Date.from(f.now.plusSeconds(600)));return c;};assertThrows(RuntimeException.class,f::read);assertEquals(0,f.reads.get());}try(var f=new Fixture("STAFF")){f.afterExchange=()->f.nanos.set(5_000_000_000L);assertThrows(RuntimeException.class,f::read);assertEquals(0,f.reads.get());}}
    @Test void currentPolicyRevocationBeforeAndAfterCustomerWithholdsObservation()throws Exception{try(var f=new Fixture("STAFF")){f.afterExchange=()->f.policy.set(false);assertThrows(RuntimeException.class,f::read);assertEquals(0,f.reads.get());}try(var f=new Fixture("STAFF")){f.afterRead=()->f.policy.set(false);assertThrows(RuntimeException.class,f::read);assertEquals(1,f.reads.get());}}
    @Test void wrongPersistedOwnerAndOwnerRefusalNeverBecomeKycAcceptance()throws Exception{try(var f=new Fixture("STAFF")){f.returnedTenant=new UUID(0,9);assertThrows(RuntimeException.class,f::read);}try(var f=new Fixture("STAFF")){f.returnedParty=new UUID(0,9);assertThrows(RuntimeException.class,f::read);}try(var f=new Fixture("STAFF")){f.returnedStatus=403;assertThrows(RuntimeException.class,f::read);}}
}
