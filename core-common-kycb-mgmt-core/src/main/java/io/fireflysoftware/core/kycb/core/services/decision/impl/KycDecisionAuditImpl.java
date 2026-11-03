package io.fireflysoftware.core.kycb.core.services.decision.impl;

import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.Permit;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.*;
import java.time.Clock;
import java.util.UUID;
import java.util.function.*;
import org.fireflyframework.core.audit.*;
import org.fireflyframework.core.audit.impl.*;
import org.fireflyframework.core.transaction.RootTransactionScope;
import org.fireflyframework.kernel.audit.*;
import reactor.core.publisher.Mono;

/** Same original bounded transaction and restricted audit login; never creates an alternate audit store. */
final class KycDecisionAuditImpl {
    private final KycDecisionSession session;private final CurrentKycDecisionAuthorityImpl authority;private final Clock clock;private final LongSupplier nanos;private final Supplier<UUID> ids;
    KycDecisionAuditImpl(KycDecisionSession session,CurrentKycDecisionAuthorityImpl authority,Clock clock,LongSupplier nanos,Supplier<UUID> ids){this.session=session;this.authority=authority;this.clock=clock;this.nanos=nanos;this.ids=ids;}
    Mono<Void> append(RootTransactionScope scope,UUID contextNonce,Permit permit,Action action,boolean replay){
        var resources=session.resources();var template=session.operations();var client=template.getDatabaseClient();
        return resources.factory().requireBoundScope(resources,scope).then(Mono.defer(()->{
            AuditEvent event=KycDecisionCodec.event(action);if(!event.actor().equals(permit.auditActor()))return Mono.error(invalid());
            AuditAppendAuthority admission=retained->authority.requireCurrent(permit).then(Mono.fromCallable(()->{if(!event.equals(retained))throw invalid();permit.remaining();return new AuditAppendAuthority.Permit(event.stream(),event.actor(),permit.until());}));
            UUID nonce=ids.get();if(nonce==null||nonce.equals(new UUID(0,0)))throw invalid();
            var appender=new SpringDataTransactionalAuditAppenderImpl(resources,client,template,resources.manager(),admission,clock,nanos,()->nonce);
            var completed=new SpringDataCompletedAuditAppendServiceImpl(resources,client,template,resources.manager(),clock,nanos);
            return appender.append(scope,event).flatMap(provisional->{
                if(provisional.replay()!=replay)return Mono.error(invalid());
                return completed.requireCompleted(scope,nonce,provisional).switchIfEmpty(Mono.error(invalid())).flatMap(record->{
                    if(!record.event().equals(event)||!record.equals(AuditRecordHasher.record(new AuditCheckpoint(event.stream(),record.sequence()-1,record.previousHash()),event,record.recordedAt())))return Mono.error(invalid());
                    var expected=new Audit(action.actionId(),action.tenantId(),action.kycCaseId(),event.eventId(),nonce,record.sequence(),record.recordHash());
                    Mono<Audit> binding=replay?session.store().audit(new Scope(action.tenantId(),action.partyId(),action.kycCaseId()),action.actionId())
                        .filter(value->value.eventId().equals(expected.eventId())&&value.auditSequence()==expected.auditSequence()&&value.auditRecordHash().equals(expected.auditRecordHash())):session.store().append(expected);
                    return binding.switchIfEmpty(Mono.error(invalid())).then(session.nativeRepository().pairAudit(contextNonce,nonce,event.eventId(),record.sequence(),record.recordHash(),replay))
                        .filter(contextNonce::equals).switchIfEmpty(Mono.error(invalid())).then();
                });
            });
        })).onErrorResume(error->resources.factory().markBoundScopeRollbackOnly(resources,scope).onErrorResume(mark->Mono.error(error)).then(Mono.error(error)));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("ORIGINAL_COMPLETED_KYC_AUDIT_REQUIRED");}
}
