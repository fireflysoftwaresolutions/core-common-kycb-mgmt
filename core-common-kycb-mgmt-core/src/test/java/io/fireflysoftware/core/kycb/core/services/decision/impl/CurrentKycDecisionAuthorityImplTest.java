/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision.impl;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Controlled native principal/PDP facts; signature, owner rows and provider effects are separate. */
class CurrentKycDecisionAuthorityImplTest {
    final Instant start=Instant.parse("2026-11-02T12:00:00Z");
    final Scope scope=new Scope(new UUID(0,1),new UUID(0,2),new UUID(0,3));
    final UUID action=new UUID(0,4),staff=new UUID(0,5);
    final Set<String> scopes=Set.of("kyc:manual-decisions:submit","kyc:manual-decisions:review","kyc:manual-decisions:revoke","kyc:manual-decisions:current","kyc:manual-decisions:recover");
    final AtomicReference<Instant> now=new AtomicReference<>(start);
    final AtomicLong nanos=new AtomicLong();
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
    final AtomicReference<SecurityPrincipal> principal=new AtomicReference<>(principal(Map.of(),scopes));
    final AtomicReference<PolicyEvaluation> evaluation=new AtomicReference<>(new PolicyEvaluation(Decision.permit(),Optional.of(new PolicyEvaluationProvenance("policy-r1","decision-1"))));
    final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<String> lastAction=new AtomicReference<>(),lastResource=new AtomicReference<>();
    final AtomicReference<Map<String,Object>> lastContext=new AtomicReference<>();
    final PolicyEvaluationPort policies=(p,a,r,c)->{calls.incrementAndGet();lastAction.set(a);lastResource.set(r);lastContext.set(c);return Mono.justOrEmpty(evaluation.get());};
    SecurityPrincipal principal(Map<String,Object> changes,Set<String> scopes){
        var claims=new HashMap<String,Object>(Map.of("tenant_id",scope.tenantId().toString(),"client_id","staff-console","principal_kind","STAFF","staff_actor_id",staff.toString(),"staff_realm","institution-staff","exp",start.plusSeconds(60),"nbf",start.minusSeconds(1),"aud",List.of("core-kyc")));
        claims.putAll(changes);return new SecurityPrincipal("original-subject","https://identity.example.test/realm",scope.tenantId().toString(),Set.of(),scopes,claims,start.minusSeconds(60),null,Set.of(),Map.of());
    }
    Profile profile(){var clients=new EnumMap<Operation,Set<String>>(Operation.class);for(var op:Operation.values())clients.put(op,op==Operation.CURRENT?Set.of("staff-console","cards-core"):Set.of("staff-console"));return new Profile("https://identity.example.test/realm","core-kyc",clients);}
    CurrentKycDecisionAuthorityImpl service(){return new CurrentKycDecisionAuthorityImpl(()->Mono.justOrEmpty(principal.get()),policies,profile(),clock,nanos::get);}
    void refused(Mono<?> request){assertEquals(403,assertThrows(ResponseStatusException.class,request::block).getStatusCode().value());}
    @Test void staffSubmissionBindsExactCasePartyActionAndSameResponseAuditProvenance(){var p=service().authorize(scope,Operation.SUBMIT,action).block();assertEquals(scope,p.scope());assertEquals(action,p.actionId());assertEquals(Operation.SUBMIT,p.operation());assertEquals(staff,p.actor().staffActorId());assertEquals(staff,p.auditActor().staffId());assertEquals("institution-staff",p.auditActor().staffRealm());assertEquals("policy-r1",p.policyRevision());assertEquals(start.plusSeconds(5),p.until());assertEquals("kyc.manual-decision.submit",lastAction.get());assertEquals("tenant/"+scope.tenantId()+"/party/"+scope.partyId()+"/kyc-case/"+scope.kycCaseId()+"/purpose/CARD_ENROLLMENT",lastResource.get());assertEquals(action.toString(),lastContext.get().get("actionId"));assertFalse(lastContext.get().containsKey("claims"));assertEquals("KycDecisionPermit",p.toString());}
    @Test void humanCanReadOnlyExactPartyCurrentDecision(){principal.set(principal(Map.of("principal_kind","HUMAN","party_id",scope.partyId().toString(),"client_id","cards-core"),scopes));var p=service().authorize(scope,Operation.CURRENT,action).block();assertNull(p.actor());assertNull(p.auditActor());for(var op:Operation.values())if(op!=Operation.CURRENT)refused(service().authorize(scope,op,action));principal.set(principal(Map.of("principal_kind","HUMAN","party_id",new UUID(0,9).toString()),scopes));refused(service().authorize(scope,Operation.CURRENT,action));}
    @Test void providerServiceCannotInventAStaffOrHumanDecision(){principal.set(principal(Map.of("principal_kind","SERVICE"),scopes));for(var op:Operation.values())refused(service().authorize(scope,op,action));assertEquals(0,calls.get());}
    @Test void missingStaffRealmOrActorIsNotSynthesized(){for(var change:List.of(Map.<String,Object>of("staff_realm",""),Map.<String,Object>of("staff_actor_id",new UUID(0,0).toString()))){principal.set(principal(change,scopes));refused(service().authorize(scope,Operation.REVIEW,action));}assertEquals(0,calls.get());}
    @Test void wrongSignedTenantAudienceClientAndTypedTimesFailBeforePolicy(){for(var change:List.of(Map.<String,Object>of("tenant_id",new UUID(0,9).toString()),Map.<String,Object>of("aud",List.of("wrong")),Map.<String,Object>of("client_id","unknown"),Map.<String,Object>of("exp",start.getEpochSecond()),Map.<String,Object>of("nbf",start.plusSeconds(1)),Map.<String,Object>of("exp",start))){principal.set(principal(change,scopes));refused(service().authorize(scope,Operation.SUBMIT,action));}assertEquals(0,calls.get());}
    @Test void scopeAndDelegatingClientArePurposeSpecific(){principal.set(principal(Map.of(),Set.of("kyc:manual-decisions:current")));refused(service().authorize(scope,Operation.REVOKE,action));principal.set(principal(Map.of("client_id","cards-core"),scopes));refused(service().authorize(scope,Operation.REVIEW,action));assertEquals(0,calls.get());}
    @Test void currentEvaluationNeedsProvenanceAndNoUnsupportedObligations(){for(var e:List.of(new PolicyEvaluation(Decision.permit(),Optional.empty()),new PolicyEvaluation(Decision.deny("denied"),Optional.empty()),new PolicyEvaluation(Decision.permit(List.of(Obligation.of("unsupported",Map.of()))),evaluation.get().provenance()))){evaluation.set(e);refused(service().authorize(scope,Operation.SUBMIT,action));}}
    @Test void unavailableEvaluationIsNotMisreportedAsRefusal(){evaluation.set(new PolicyEvaluation(Decision.indeterminate("unavailable"),Optional.empty()));assertEquals(503,assertThrows(ResponseStatusException.class,()->service().authorize(scope,Operation.SUBMIT,action).block()).getStatusCode().value());evaluation.set(null);assertEquals(503,assertThrows(ResponseStatusException.class,()->service().authorize(scope,Operation.SUBMIT,action).block()).getStatusCode().value());}
    @Test void changedPrincipalAfterPolicyCannotProducePermit(){var original=principal.get();var count=new AtomicInteger();var service=new CurrentKycDecisionAuthorityImpl(()->Mono.just(count.getAndIncrement()==0?original:principal(Map.of("staff_actor_id",new UUID(0,7).toString()),scopes)),policies,profile(),clock,nanos::get);refused(service.authorize(scope,Operation.REVIEW,action));}
    @Test void repeatedCurrentEvaluationPreservesOriginalDeadlineAndPolicyRevision(){var service=service();var permit=service.authorize(scope,Operation.SUBMIT,action).block();nanos.set(4_000_000_000L);service.requireCurrent(permit).block();assertEquals(2,calls.get());assertEquals(start.plusSeconds(5),permit.until());evaluation.set(new PolicyEvaluation(Decision.permit(),Optional.of(new PolicyEvaluationProvenance("changed","next"))));refused(service.requireCurrent(permit));}
    @Test void postReadRevocationAndPrincipalReplacementWithholdCurrentReceipt(){var service=service();var permit=service.authorize(scope,Operation.CURRENT,action).block();evaluation.set(new PolicyEvaluation(Decision.deny("revoked"),Optional.empty()));refused(service.requireCurrent(permit));principal.set(principal(Map.of("staff_actor_id",new UUID(0,9).toString()),scopes));refused(service.requireCurrent(permit));}
    @Test void bothOriginalClocksRejectExpiryAndBackwardMovement(){for(boolean wall:List.of(true,false)){var service=service();now.set(start);nanos.set(0);var permit=service.authorize(scope,Operation.CURRENT,action).block();if(wall)now.set(start.plusSeconds(5));else nanos.set(5_000_000_000L);refused(service.requireCurrent(permit));}now.set(start);nanos.set(0);var service=service();var permit=service.authorize(scope,Operation.CURRENT,action).block();now.set(start.minusSeconds(1));refused(service.requireCurrent(permit));now.set(start);nanos.set(-1);refused(service.requireCurrent(permit));}
    @Test void permitCannotMoveToAnotherAuthorityInstance(){var original=service();var permit=original.authorize(scope,Operation.CURRENT,action).block();refused(service().requireCurrent(permit));}
    @Test void tokenExpiryNarrowsOriginalPermit(){principal.set(principal(Map.of("exp",start.plusSeconds(2)),scopes));assertEquals(start.plusSeconds(2),service().authorize(scope,Operation.CURRENT,action).block().until());}
    @Test void missingPrincipalNeverReachesPolicy(){principal.set(null);refused(service().authorize(scope,Operation.CURRENT,action));assertEquals(0,calls.get());}
    @Test void stalledPolicyFailsWithinOriginalBudget(){var service=new CurrentKycDecisionAuthorityImpl(()->Mono.just(principal.get()),(p,a,r,c)->Mono.never(),profile(),clock,nanos::get);StepVerifier.withVirtualTime(()->service.authorize(scope,Operation.SUBMIT,action)).thenAwait(Duration.ofSeconds(5)).expectErrorMatches(e->e instanceof ResponseStatusException s&&s.getStatusCode().value()==503).verify(Duration.ofSeconds(1));}
}
