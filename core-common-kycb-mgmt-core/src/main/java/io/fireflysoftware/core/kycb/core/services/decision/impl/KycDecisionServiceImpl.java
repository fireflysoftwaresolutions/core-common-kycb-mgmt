/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision.impl;

import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.core.services.decision.KycDecisionException.Reason;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.*;
import io.fireflysoftware.core.kycb.core.services.decision.party.KycPartyReader;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Action;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Control;
import io.fireflysoftware.core.kycb.models.repositories.decision.KycDecisionNativeRepository.Stamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import org.fireflyframework.core.transaction.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Explicit manual individual-party decisions; provider verification and card issuance are separate operations. */
public final class KycDecisionServiceImpl implements KycDecisionService {
    private final CurrentKycDecisionAuthorityImpl authority;private final KycPartyReader parties;
    private final KycDecisionSession command,reader;private final KycDecisionAuditImpl audit;private final Duration maximumValidity;
    private final ZoneId sourceExpiryZone;private final Clock clock;private final LongSupplier nanos;private final Supplier<UUID> ids;
    public KycDecisionServiceImpl(CurrentKycDecisionAuthorityImpl authority,KycPartyReader parties,KycDecisionSession command,KycDecisionSession reader,
            Duration maximumValidity,ZoneId sourceExpiryZone,Clock clock,LongSupplier nanos,Supplier<UUID> ids){
        this.authority=Objects.requireNonNull(authority);this.parties=Objects.requireNonNull(parties);this.command=Objects.requireNonNull(command);this.reader=Objects.requireNonNull(reader);
        this.maximumValidity=Objects.requireNonNull(maximumValidity);if(maximumValidity.isNegative()||maximumValidity.isZero()||maximumValidity.getNano()!=0)throw new IllegalArgumentException("EXPLICIT_MANUAL_KYC_VALIDITY_POLICY_REQUIRED");
        this.sourceExpiryZone=Objects.requireNonNull(sourceExpiryZone);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);this.ids=Objects.requireNonNull(ids);
        if(command.role()!=KycDecisionSession.Role.COMMAND||reader.role()!=KycDecisionSession.Role.READER||command.operations()==reader.operations()||command.resources().factory()==reader.resources().factory())throw new IllegalArgumentException("DISTINCT_OWNED_KYC_ROLE_SESSIONS_REQUIRED");
        this.audit=new KycDecisionAuditImpl(command,authority,clock,nanos,ids);
    }
    public Mono<ActionReceipt> submit(Scope scope,SubmitRequest request){Objects.requireNonNull(request);return execute(scope,Operation.SUBMIT,request.actionId(),request.actionId(),request.expectedControlVersion(),request,(ctx)->mutate(ctx,request).map(KycDecisionCodec::receipt));}
    public Mono<ActionReceipt> review(Scope scope,ReviewRequest request){Objects.requireNonNull(request);return execute(scope,Operation.REVIEW,request.actionId(),request.submissionActionId(),request.expectedControlVersion(),request,(ctx)->mutate(ctx,request).map(KycDecisionCodec::receipt));}
    public Mono<ActionReceipt> revoke(Scope scope,RevokeRequest request){Objects.requireNonNull(request);return execute(scope,Operation.REVOKE,request.actionId(),request.selectedActionId(),request.expectedControlVersion(),request,(ctx)->mutate(ctx,request).map(KycDecisionCodec::receipt));}
    public Mono<ActionReceipt> recover(Scope scope,UUID actionId){return execute(scope,Operation.RECOVER,actionId,actionId,null,null,ctx->originalAction(ctx,actionId).map(action->{sameActor(ctx.permit(),action);return KycDecisionCodec.receipt(action);}));}
    public Mono<CurrentDecision> current(Scope scope,UUID decisionId){return execute(scope,Operation.CURRENT,decisionId,decisionId,null,null,ctx->
        reader.store().control(scope).filter(control->control.state().equals("APPROVED")&&decisionId.equals(control.reviewActionId())).switchIfEmpty(Mono.error(failure(decisionId,Reason.REFUSED)))
            .flatMap(control->originalAction(ctx,decisionId).flatMap(reviewAction->{
                if(!reviewAction.operation().equals("REVIEW")||!reviewAction.selectionId().equals(control.submissionActionId()))throw failure(decisionId,Reason.REFUSED);
                Review review=KycDecisionCodec.decode(reviewAction.payload(),Review.class);
                return originalAction(ctx,control.submissionActionId()).flatMap(submissionAction->{
                    Submission submission=submission(submissionAction);Instant until=Collections.min(List.of(ctx.permit().until(),ctx.stamp().commandUntil(),submission.validUntil())).truncatedTo(ChronoUnit.MICROS);
                    return verifyEvidence(ctx,submission).then(Mono.fromCallable(()->KycManualDecisionRules.current(submission,review,false,control.version(),ctx.stamp().databaseTime(),until)));
                });
            })));
    }
    private record Context(KycDecisionSession session,RootTransactionScope root,UUID nonce,Permit permit,Stamp stamp,UUID selection,Long version,String intent) {}
    private <T> Mono<T> execute(Scope scope,Operation operation,UUID action,UUID selection,Long version,Object request,Function<Context,Mono<T>> work){
        return Mono.defer(()->{
            Objects.requireNonNull(scope);requireId(action);requireId(selection);Instant started=clock.instant();long before=nanos.getAsLong();
            var budget=R2dbcOperationBudget.observed(started,started.plusSeconds(5),before,Duration.ofSeconds(5),clock,nanos);
            return authority.authorize(scope,operation,action).timeout(budget.remaining()).flatMap(permit->parties.observe(permit).timeout(budget.remaining()).flatMap(party->{
                requireParty(scope,party);permit.remaining();
                var owned=budget.intersect(R2dbcOperationBudget.observed(permit.observedAt(),Collections.min(List.of(permit.until(),party.effectiveUntil())),before,Duration.ofSeconds(5),clock,nanos));
                var session=operation==Operation.CURRENT||operation==Operation.RECOVER?reader:command;
                String intent=request==null?null:KycDecisionCodec.intent(scope,permit.actor(),CommandKind.valueOf(operation.name()),request);
                UUID nonce=nextId();var definition=new DefaultTransactionDefinition();definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);definition.setReadOnly(false);
                UUID[] documents=request instanceof SubmitRequest submission?submission.evidence().stream().map(Evidence::documentId).toArray(UUID[]::new):new UUID[0];
                var responseBudget=new AtomicReference<>(owned);var error=new AtomicReference<Throwable>();long nativeBefore=nanos.getAsLong();Instant until=Collections.min(List.of(permit.until(),party.effectiveUntil(),started.plusSeconds(5)));
                return session.transactions().execute(owned,definition,root->session.nativeRepository().enter(nonce,scope.tenantId(),scope.partyId(),scope.kycCaseId(),action,selection,operation.name(),intent,version,documents,
                    permit.issuer(),permit.subject(),permit.clientId(),permit.platformTenantId(),permit.auditActor()==null?null:permit.auditActor().staffRealm(),permit.actor()==null?null:permit.actor().staffActorId(),permit.policyRevision(),sourceExpiryZone.getId(),permit.until().atOffset(ZoneOffset.UTC),until.atOffset(ZoneOffset.UTC))
                    .switchIfEmpty(Mono.error(failure(action,Reason.UNAVAILABLE))).flatMap(stamp->{
                        if(!nonce.equals(stamp.contextNonce())||stamp.databaseTime()==null||stamp.commandUntil()==null||stamp.commandUntil().isAfter(until))throw failure(action,Reason.UNAVAILABLE);
                        var narrowed=owned.intersect(R2dbcOperationBudget.observed(stamp.databaseTime(),stamp.commandUntil().minusMillis(1),nativeBefore,Duration.ofSeconds(5),clock,nanos));
                        root.narrow(narrowed);responseBudget.set(narrowed);
                        var context=new Context(session,root,nonce,permit,stamp,selection,version,intent);
                        return session.store().readGuardedCase(scope).filter(row->row.partyId().equals(scope.partyId())&&row.kycVerificationId().equals(scope.kycCaseId())).switchIfEmpty(Mono.error(failure(action,Reason.REFUSED)))
                            .then(Mono.defer(()->work.apply(context))).flatMap(value->authority.requireCurrent(permit)
                                .then(session.nativeRepository().complete(nonce)).filter(nonce::equals).switchIfEmpty(Mono.error(failure(action,Reason.UNAVAILABLE))).thenReturn(value));
                    }).doOnError(error::set)).onErrorMap(RootTransactionException.class,failed->transactionFailure(action,failed,error.get()))
                    .flatMap(completed->{
                        if(!completed.transactionFacts().boundaryCompleted()||!completed.transactionFacts().commitAcknowledged())return Mono.error(new KycDecisionException(action,Reason.UNKNOWN,completed.transactionFacts(),completed.cleanupFact()));
                        return Mono.defer(()->parties.observe(permit).timeout(responseBudget.get().remaining()).doOnNext(current->requireParty(scope,current)).then(authority.requireCurrent(permit))
                            .then(Mono.fromCallable(()->{responseBudget.get().remaining();if(completed.value() instanceof CurrentDecision decision&&!clock.instant().isBefore(decision.effectiveUntil()))throw failure(action,Reason.WITHHELD);return completed.value();})))
                            .onErrorMap(failed->new KycDecisionException(action,Reason.WITHHELD,completed.transactionFacts(),completed.cleanupFact()));
                    });
            }));
        }).onErrorMap(error->error instanceof KycDecisionException?error:failure(action,refused(error)?Reason.REFUSED:Reason.UNAVAILABLE));
    }
    private Mono<Action> mutate(Context ctx,Object request){
        var scope=ctx.permit().scope();var action=ctx.permit().actionId();var store=ctx.session().store();
        return store.action(scope,action).flatMap(stored->{
            sameActor(ctx.permit(),stored);if(!stored.operation().equals(ctx.permit().operation().name())||!stored.selectionId().equals(ctx.selection())||!stored.intentDigest().equals(ctx.intent()))throw failure(action,Reason.CONFLICT);
            KycDecisionCodec.event(stored);return audit.append(ctx.root(),ctx.nonce(),ctx.permit(),stored,true).thenReturn(stored);
        }).switchIfEmpty(Mono.defer(()->store.control(scope).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(current->{
            Control old=current.orElse(null);long version=old==null?-1:old.version();if(!Objects.equals(ctx.version(),version))throw failure(action,Reason.CONFLICT);
            if(request instanceof SubmitRequest submit){
                if(old!=null&&!Set.of("REJECTED","REVOKED").contains(old.state()))throw failure(action,Reason.CONFLICT);
                return resolveSourceExpiries(ctx,submit.evidence()).flatMap(expiries->{
                    Submission submission=KycManualDecisionRules.submit(action,scope,ctx.permit().actor(),submit.evidence(),expiries,ctx.stamp().databaseTime(),submit.validUntil(),ctx.stamp().databaseTime().plus(maximumValidity));
                    var next=new Control(scope.kycCaseId(),scope.tenantId(),scope.partyId(),action,null,"SUBMITTED",Math.addExact(version,1));
                    Action row=action(ctx,submission,"SUBMITTED",next.version());
                    return store.append(row).flatMap(saved->retainEvidence(ctx,submission).then(old==null?store.insert(next):store.transition(old,next)).then(audit.append(ctx.root(),ctx.nonce(),ctx.permit(),saved,false)).thenReturn(saved));
                });
            }
            if(old==null)throw failure(action,Reason.NOT_FOUND);
            if(request instanceof ReviewRequest review){
                if(!old.state().equals("SUBMITTED")||!old.submissionActionId().equals(review.submissionActionId()))throw failure(action,Reason.CONFLICT);
                return originalAction(ctx,review.submissionActionId()).flatMap(original->{
                    Submission submitted=submission(original);if(!submitted.digest().equals(review.submissionDigest()))throw failure(action,Reason.CONFLICT);
                    Review reviewed=KycManualDecisionRules.review(action,submitted,ctx.permit().actor(),review.outcome(),review.reasonCode(),ctx.stamp().databaseTime());
                    String state=review.outcome()==ReviewOutcome.APPROVE?"APPROVED":"REJECTED";var next=new Control(old.kycCaseId(),old.tenantId(),old.partyId(),old.submissionActionId(),action,state,Math.addExact(version,1));
                    Action row=action(ctx,reviewed,state,next.version());
                    return verifyEvidence(ctx,submitted).then(store.append(row)).flatMap(saved->store.transition(old,next).then(audit.append(ctx.root(),ctx.nonce(),ctx.permit(),saved,false)).thenReturn(saved));
                });
            }
            if(request instanceof RevokeRequest revoke){
                if(!Set.of("SUBMITTED","APPROVED").contains(old.state())||!Objects.equals(old.reviewActionId()==null?old.submissionActionId():old.reviewActionId(),revoke.selectedActionId()))throw failure(action,Reason.CONFLICT);
                var next=new Control(old.kycCaseId(),old.tenantId(),old.partyId(),old.submissionActionId(),old.reviewActionId(),"REVOKED",Math.addExact(version,1));Action row=action(ctx,revoke,"REVOKED",next.version());
                return store.append(row).flatMap(saved->store.transition(old,next).then(audit.append(ctx.root(),ctx.nonce(),ctx.permit(),saved,false)).thenReturn(saved));
            }
            throw failure(action,Reason.REFUSED);
        })));
    }
    private Action action(Context ctx,Object payload,String state,long version){
        var permit=ctx.permit();var scope=permit.scope();var actor=permit.auditActor();String body=KycDecisionCodec.encode(payload);
        return new Action(permit.actionId(),scope.tenantId(),scope.partyId(),scope.kycCaseId(),nextId(),nextId(),permit.operation().name(),ctx.selection(),ctx.intent(),body,KycDecisionCodec.payloadDigest(body),state,version,ctx.stamp().databaseTime(),actor.issuer(),actor.subject(),actor.clientId(),actor.platformTenantId(),actor.staffRealm(),actor.staffId(),permit.policyRevision());
    }
    private Mono<List<SourceExpiry>> resolveSourceExpiries(Context ctx,List<Evidence> evidence){
        return Flux.fromIterable(evidence).concatMap(value->ctx.session().store().readGuardedDocument(ctx.permit().scope(),value.documentId())
            .switchIfEmpty(Mono.error(failure(ctx.permit().actionId(),Reason.REFUSED))).map(row->{
                if(!Objects.equals(row.documentSystemId(),value.sourceSystem())||!Objects.equals(row.documentReference(),value.sourceReference())||row.documentType()==null)throw failure(ctx.permit().actionId(),Reason.REFUSED);
                var expiry=KycSourceExpiryRules.resolve(row.expiryDate(),sourceExpiryZone,ctx.stamp().databaseTime(),value.validUntil());
                return new SourceExpiry(value.documentId(),row.expiryDate(),expiry==null?null:expiry.zoneId(),expiry==null?null:expiry.offsetSeconds(),expiry==null?null:expiry.expiresAt());
            })).collectList();
    }
    private static SourceExpiry sourceExpiry(Submission submission,UUID document){return submission.sourceExpiries().stream().filter(value->value.documentId().equals(document)).findFirst().orElseThrow(()->failure(submission.actionId(),Reason.REFUSED));}
    private Mono<Void> retainEvidence(Context ctx,Submission submission){return Flux.fromIterable(submission.evidence()).concatMap(evidence->ctx.session().store().readGuardedDocument(submission.scope(),evidence.documentId()).switchIfEmpty(Mono.error(failure(ctx.permit().actionId(),Reason.REFUSED))).flatMap(row->{
        if(!Objects.equals(row.documentSystemId(),evidence.sourceSystem())||!Objects.equals(row.documentReference(),evidence.sourceReference())||row.documentType()==null)throw failure(ctx.permit().actionId(),Reason.REFUSED);
        var expiry=sourceExpiry(submission,evidence.documentId());if(!Objects.equals(row.expiryDate(),expiry.sourceDate()))throw failure(submission.actionId(),Reason.REFUSED);
        return ctx.session().store().append(new KycDecisionRows.Evidence(nextId(),submission.scope().tenantId(),submission.scope().partyId(),submission.scope().kycCaseId(),submission.actionId(),evidence.documentId(),row.identityDocumentId(),row.documentType(),row.verificationPurpose(),row.documentReference(),row.documentSystemId(),row.expiryDate(),expiry.zoneId(),expiry.offsetSeconds(),expiry.expiresAt(),evidence.contentDigest(),evidence.collectedAt(),evidence.validUntil()));
    })).then();}
    private Mono<Void> verifyEvidence(Context ctx,Submission submission){return ctx.session().store().evidence(submission.scope(),submission.actionId()).collectList().flatMap(rows->{
        if(rows.size()!=submission.evidence().size())throw failure(ctx.permit().actionId(),Reason.REFUSED);
        var expected=new HashMap<UUID,Evidence>();submission.evidence().forEach(value->expected.put(value.documentId(),value));
        return Flux.fromIterable(rows).concatMap(row->{
            if(!sourceExpiry(submission,row.documentId()).equals(new SourceExpiry(row.documentId(),row.sourceExpiryDate(),row.sourceExpiryZone(),row.sourceExpiryOffsetSeconds(),row.sourceExpiresAt())))throw failure(submission.actionId(),Reason.REFUSED);
            KycSourceExpiryRules.requireRetained(row.sourceExpiryDate(),row.sourceExpiryZone(),row.sourceExpiryOffsetSeconds(),row.sourceExpiresAt(),ctx.stamp().databaseTime(),row.validUntil());
            Evidence value=expected.remove(row.documentId());if(value==null||!Objects.equals(value.sourceSystem(),row.documentSystemId())||!Objects.equals(value.sourceReference(),row.documentReference())||!value.contentDigest().equals(row.contentDigest())||!value.collectedAt().equals(row.collectedAt())||!value.validUntil().equals(row.validUntil()))throw failure(ctx.permit().actionId(),Reason.REFUSED);
            return ctx.session().store().readGuardedDocument(submission.scope(),row.documentId()).filter(source->Objects.equals(source.identityDocumentId(),row.identityDocumentId())&&Objects.equals(source.documentType(),row.documentType())&&Objects.equals(source.verificationPurpose(),row.verificationPurpose())&&Objects.equals(source.documentReference(),row.documentReference())&&Objects.equals(source.documentSystemId(),row.documentSystemId())&&Objects.equals(source.expiryDate(),row.sourceExpiryDate()))
                .switchIfEmpty(Mono.error(failure(ctx.permit().actionId(),Reason.REFUSED)));
        }).then();
    });}
    private Mono<Action> originalAction(Context ctx,UUID action){return ctx.session().store().action(ctx.permit().scope(),action).switchIfEmpty(Mono.error(failure(ctx.permit().actionId(),Reason.NOT_FOUND))).flatMap(row->{KycDecisionCodec.event(row);return ctx.session().store().audit(ctx.permit().scope(),action).filter(binding->binding.eventId().equals(row.eventId())).switchIfEmpty(Mono.error(failure(ctx.permit().actionId(),Reason.UNAVAILABLE))).thenReturn(row);});}
    private static Submission submission(Action row){if(!row.operation().equals("SUBMIT"))throw failure(row.actionId(),Reason.REFUSED);Submission value=KycDecisionCodec.decode(row.payload(),Submission.class);KycManualDecisionRules.requireSubmission(value);if(!value.scope().equals(new Scope(row.tenantId(),row.partyId(),row.kycCaseId()))||!value.actionId().equals(row.actionId()))throw failure(row.actionId(),Reason.REFUSED);return value;}
    private static void sameActor(Permit permit,Action row){if(permit.actor()==null||!permit.actor().staffActorId().equals(row.staffActorId())||!permit.issuer().equals(row.actorIssuer())||!permit.subject().equals(row.actorSubject()))throw failure(permit.actionId(),Reason.REFUSED);}
    private static void requireParty(Scope scope,KycPartyReader.Observation party){if(party==null||!scope.tenantId().equals(party.tenantId())||!scope.partyId().equals(party.partyId())||!"INDIVIDUAL".equals(party.partyKind()))throw new IllegalArgumentException("CURRENT_INDIVIDUAL_CUSTOMER_REQUIRED");}
    private UUID nextId(){UUID id=ids.get();requireId(id);return id;}
    private static void requireId(UUID id){if(id==null||id.equals(new UUID(0,0)))throw new IllegalArgumentException("ORIGINAL_KYC_ACTION_REQUIRED");}
    private static boolean refused(Throwable error){return error instanceof ResponseStatusException status&&status.getStatusCode().value()==403||error instanceof IllegalArgumentException;}
    private static KycDecisionException failure(UUID action,Reason reason){return new KycDecisionException(action,reason,new TransactionFacts(false,false,false,false),CleanupFact.COMPLETE);}
    private static KycDecisionException transactionFailure(UUID action,RootTransactionException failed,Throwable work){
        var facts=failed.transactionFacts();Reason reason=facts.commitAcknowledged()?Reason.WITHHELD:Reason.UNKNOWN;
        if(!facts.boundaryCompleted()&&!facts.commitAcknowledged()&&facts.rollbackAcknowledged()&&failed.cleanupFact()==CleanupFact.COMPLETE)reason=work instanceof KycDecisionException known?known.reason():refused(work)?Reason.REFUSED:Reason.UNAVAILABLE;
        return new KycDecisionException(action,reason,facts,failed.cleanupFact());
    }
}
