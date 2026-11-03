/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision.impl;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Actor;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.kernel.audit.AuditActor;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Current native identity and same-response PDP provenance; owner rows remain separately required. */
public final class CurrentKycDecisionAuthorityImpl {
    public enum Operation { SUBMIT, REVIEW, REVOKE, CURRENT, RECOVER }
    public record Profile(String issuer,String audience,Map<Operation,Set<String>> clients) {
        public Profile {
            text(issuer,512);text(audience,255);URI uri=URI.create(issuer);
            if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost())))throw invalid();
            var copy=new EnumMap<Operation,Set<String>>(Operation.class);
            for(var operation:Operation.values()){
                var values=Set.copyOf(Objects.requireNonNull(clients.get(operation)));if(values.isEmpty())throw invalid();values.forEach(value->text(value,255));copy.put(operation,values);
            }
            clients=Map.copyOf(copy);
        }
        @Override public String toString(){return "KycDecisionIdentityProfile";}
    }
    private final SecurityContextPort security;
    private final PolicyEvaluationPort policies;
    private final Profile profile;
    private final Clock clock;
    private final LongSupplier nanos;
    public CurrentKycDecisionAuthorityImpl(SecurityContextPort security,PolicyEvaluationPort policies,Profile profile,Clock clock,LongSupplier nanos){
        this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);this.profile=Objects.requireNonNull(profile);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    public final class Permit {
        private final CurrentKycDecisionAuthorityImpl owner=CurrentKycDecisionAuthorityImpl.this;
        private final SecurityPrincipal principal;
        private final Scope scope;
        private final Operation operation;
        private final UUID actionId;
        private final Identity identity;
        private final String revision;
        private final Instant observed,until;
        private final long started,budget;
        private Permit(SecurityPrincipal principal,Scope scope,Operation operation,UUID actionId,Identity identity,String revision,Instant observed,long started){
            this.principal=principal;this.scope=scope;this.operation=operation;this.actionId=actionId;this.identity=identity;this.revision=revision;this.observed=observed;this.started=started;
            this.until=identity.expires().isBefore(observed.plusSeconds(5))?identity.expires():observed.plusSeconds(5);this.budget=Duration.between(observed,until).toNanos();remaining();
        }
        public Scope scope(){return scope;}
        public Operation operation(){return operation;}
        public UUID actionId(){return actionId;}
        public Actor actor(){return identity.actor();}
        public AuditActor auditActor(){return identity.auditActor();}
        public String policyRevision(){return revision;}
        public String issuer(){return principal.issuer();}
        public String subject(){return principal.subject();}
        public String clientId(){return identity.client();}
        public String platformTenantId(){return principal.tenantId();}
        public Instant observedAt(){return observed;}
        public Instant until(){return until;}
        public Duration remaining(){
            Instant now=clock.instant();long elapsed=nanos.getAsLong()-started;
            if(now.isBefore(observed)||!now.isBefore(until)||elapsed<0||elapsed>=budget)throw denied();
            Duration wall=Duration.between(now,until),monotonic=Duration.ofNanos(budget-elapsed);return wall.compareTo(monotonic)<0?wall:monotonic;
        }
        @Override public String toString(){return "KycDecisionPermit";}
    }
    public Mono<Permit> authorize(Scope scope,Operation operation,UUID actionId){
        return Mono.defer(()->{
            Objects.requireNonNull(scope);Objects.requireNonNull(operation);id(actionId);Instant observed=clock.instant();long started=nanos.getAsLong();
            return Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(denied())).flatMap(principal->{
                Identity identity=identity(principal,scope,operation);
                return evaluate(principal,scope,operation,actionId,identity).flatMap(revision->Mono.defer(security::currentPrincipal)
                    .switchIfEmpty(Mono.error(denied())).map(current->{
                        if(!principal.equals(current)||!identity.equals(identity(current,scope,operation)))throw denied();
                        return new Permit(current,scope,operation,actionId,identity,revision,observed,started);
                    }));
            });
        }).timeout(Duration.ofSeconds(5)).onErrorMap(CurrentKycDecisionAuthorityImpl::safeFailure);
    }
    public Mono<Void> requireCurrent(Permit permit){
        return Mono.defer(()->{
            if(permit==null||permit.owner!=this)throw denied();permit.remaining();
            return Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(denied())).flatMap(principal->{
                if(!permit.principal.equals(principal)||!permit.identity.equals(identity(principal,permit.scope,permit.operation)))throw denied();
                return evaluate(principal,permit.scope,permit.operation,permit.actionId,permit.identity).filter(permit.revision::equals)
                    .switchIfEmpty(Mono.error(denied())).then(Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(denied())))
                    .doOnNext(current->{if(!principal.equals(current)||!permit.identity.equals(identity(current,permit.scope,permit.operation)))throw denied();permit.remaining();}).then();
            }).timeout(permit.remaining());
        }).onErrorMap(CurrentKycDecisionAuthorityImpl::safeFailure);
    }
    private Mono<String> evaluate(SecurityPrincipal principal,Scope scope,Operation operation,UUID actionId,Identity identity){
        String op=operation.name().toLowerCase(Locale.ROOT);
        String resource="tenant/"+scope.tenantId()+"/party/"+scope.partyId()+"/kyc-case/"+scope.kycCaseId()+"/purpose/CARD_ENROLLMENT";
        var context=new HashMap<String,Object>();context.put("tenantId",scope.tenantId().toString());context.put("partyId",scope.partyId().toString());context.put("kycCaseId",scope.kycCaseId().toString());context.put("actionId",actionId.toString());context.put("purpose","CARD_ENROLLMENT");context.put("decisionMode","MANUAL_REVIEW");context.put("operation",operation.name());context.put("clientId",identity.client());
        if(identity.actor()!=null)context.put("staffActorId",identity.actor().staffActorId().toString());
        return Mono.defer(()->policies.evaluate(principal,"kyc.manual-decision."+op,resource,Map.copyOf(context)))
            .switchIfEmpty(Mono.error(unavailable())).flatMap(evaluation->{
                if(evaluation.decision().effect()==Decision.Effect.INDETERMINATE)return Mono.error(unavailable());
                if(evaluation.decision().effect()!=Decision.Effect.PERMIT||!evaluation.decision().obligations().isEmpty()||evaluation.provenance().isEmpty())return Mono.error(denied());
                String revision=evaluation.provenance().orElseThrow().policyRevision();text(revision,512);return Mono.just(revision);
            });
    }
    private record Identity(String client,Instant expires,Actor actor,AuditActor auditActor) {}
    private Identity identity(SecurityPrincipal principal,Scope scope,Operation operation){
        Instant now=clock.instant();String client=claim(principal,"client_id"),tenant=claim(principal,"tenant_id"),kind=claim(principal,"principal_kind");
        Object expiry=principal.claims().get("exp"),starts=principal.claims().get("nbf");
        if(!profile.issuer().equals(principal.issuer())||principal.subject()==null||principal.subject().isBlank()
                ||!scope.tenantId().toString().equals(tenant)||!tenant.equals(principal.tenantId())
                ||!List.of(profile.audience()).equals(principal.claims().get("aud"))||!profile.clients().get(operation).contains(client)
                ||!principal.hasScope("kyc:manual-decisions:"+operation.name().toLowerCase(Locale.ROOT))
                ||!(expiry instanceof Instant expires)||!(starts instanceof Instant notBefore)||!now.isBefore(expires)||now.isBefore(notBefore)||!notBefore.isBefore(expires))throw denied();
        if("STAFF".equals(kind)){
            UUID staff=uuid(claim(principal,"staff_actor_id"));String realm=claim(principal,"staff_realm");
            return new Identity(client,expires,new Actor(principal.issuer(),principal.subject(),staff),new AuditActor(principal.issuer(),principal.subject(),client,tenant,realm,staff));
        }
        if("HUMAN".equals(kind)&&operation==Operation.CURRENT&&scope.partyId().equals(uuid(claim(principal,"party_id"))))return new Identity(client,expires,null,null);
        throw denied();
    }
    private static String claim(SecurityPrincipal principal,String name){Object value=principal.claims().get(name);if(!(value instanceof String text))throw denied();try{text(text,512);}catch(RuntimeException error){throw denied();}return text;}
    private static UUID uuid(String value){if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw denied();UUID id=UUID.fromString(value);id(id);return id;}
    private static void id(UUID value){if(value==null||value.equals(new UUID(0,0)))throw denied();}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.codePoints().anyMatch(Character::isISOControl))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("EXPLICIT_KYC_DECISION_IDENTITY_PROFILE_REQUIRED");}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Current purpose-specific KYC authority required");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Current KYC authority observation unavailable");}
    private static Throwable safeFailure(Throwable error){return error instanceof ResponseStatusException?error:unavailable();}
    @Override public String toString(){return "CurrentKycDecisionAuthority";}
}
