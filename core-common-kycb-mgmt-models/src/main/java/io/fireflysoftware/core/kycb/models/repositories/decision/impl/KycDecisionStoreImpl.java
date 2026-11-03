/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.models.repositories.decision.impl;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.*;
import io.fireflysoftware.core.kycb.models.repositories.decision.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** All ordinary writes retain the exact transaction-bound template mapping/callback graph. */
public final class KycDecisionStoreImpl implements KycDecisionStore {
    private final R2dbcEntityTemplate operations;
    private final KycDecisionCaseRepository cases;
    private final KycDecisionDocumentRepository documents;
    public KycDecisionStoreImpl(R2dbcEntityTemplate operations){
        this.operations=Objects.requireNonNull(operations);
        var factory=new R2dbcRepositoryFactory(operations);factory.setBeanClassLoader(KycDecisionCaseRepository.class.getClassLoader());
        this.cases=factory.getRepository(KycDecisionCaseRepository.class);this.documents=factory.getRepository(KycDecisionDocumentRepository.class);
    }
    public Mono<Case> readGuardedCase(Scope scope){return cases.findByKycVerificationIdAndPartyId(scope.kycCaseId(),scope.partyId());}
    public Mono<Document> readGuardedDocument(Scope scope,UUID documentId){return documents.findByKycVerificationIdAndVerificationDocumentId(scope.kycCaseId(),documentId);}
    public Mono<Control> control(Scope scope){return operations.selectOne(Query.query(criteria(scope)),Control.class);}
    public Mono<Control> insert(Control row){return insertExact(row,control(new Scope(row.tenantId(),row.partyId(),row.kycCaseId())));}
    public Mono<Control> transition(Control previous,Control next){
        if(!previous.tenantId().equals(next.tenantId())||!previous.partyId().equals(next.partyId())||!previous.kycCaseId().equals(next.kycCaseId())||next.version()!=Math.addExact(previous.version(),1))return Mono.error(changed());
        Scope scope=new Scope(previous.tenantId(),previous.partyId(),previous.kycCaseId());
        var query=Query.query(criteria(scope).and("version").is(previous.version()).and("state").is(previous.state()).and("submission_action_id").is(previous.submissionActionId()));
        var update=Update.update("submission_action_id",next.submissionActionId()).set("review_action_id",next.reviewActionId()).set("state",next.state()).set("version",next.version());
        return operations.update(query,update,Control.class).filter(count->count==1).switchIfEmpty(Mono.error(changed())).then(control(scope)).filter(next::equals).switchIfEmpty(Mono.error(changed()));
    }
    public Mono<Action> action(Scope scope,UUID actionId){return operations.selectOne(Query.query(criteria(scope).and("action_id").is(actionId)),Action.class);}
    public Mono<Action> append(Action row){return insertExact(row,action(new Scope(row.tenantId(),row.partyId(),row.kycCaseId()),row.actionId()));}
    public Flux<Evidence> evidence(Scope scope,UUID submissionActionId){return operations.select(Query.query(criteria(scope).and("submission_action_id").is(submissionActionId)).sort(org.springframework.data.domain.Sort.by("document_id")),Evidence.class);}
    public Mono<Evidence> append(Evidence row){return insertExact(row,operations.selectOne(Query.query(criteria(new Scope(row.tenantId(),row.partyId(),row.kycCaseId())).and("binding_id").is(row.bindingId())),Evidence.class));}
    public Mono<Audit> audit(Scope scope,UUID actionId){return operations.selectOne(Query.query(Criteria.where("tenant_id").is(scope.tenantId()).and("kyc_case_id").is(scope.kycCaseId()).and("action_id").is(actionId)),Audit.class);}
    public Mono<Audit> append(Audit row){return insertExact(row,operations.selectOne(Query.query(Criteria.where("tenant_id").is(row.tenantId()).and("kyc_case_id").is(row.kycCaseId()).and("action_id").is(row.actionId())),Audit.class));}
    private <T> Mono<T> insertExact(T row,Mono<T> readback){return operations.insert(row).filter(row::equals).switchIfEmpty(Mono.error(changed())).then(readback).filter(row::equals).switchIfEmpty(Mono.error(changed()));}
    private static Criteria criteria(Scope scope){return Criteria.where("tenant_id").is(scope.tenantId()).and("party_id").is(scope.partyId()).and("kyc_case_id").is(scope.kycCaseId());}
    private static IllegalStateException changed(){return new IllegalStateException("EXACT_MAPPED_KYC_DECISION_WRITE_REQUIRED");}
}
