/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.providers.verification;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;

/** Replaceable external boundary. A provider outcome cannot authorize a KYC business decision by itself. */
public interface KycVerificationProvider {
    enum State { NOT_CONFIGURED, PENDING, VERIFIED, REJECTED, UNKNOWN }
    enum Execution { NOT_EXECUTED, EXECUTED, UNKNOWN }
    record Initiation(UUID actionId,Scope scope,String evidenceDigest,Instant notAfter) {
        public Initiation {identity(actionId,scope);digest(evidenceDigest);if(notAfter==null||notAfter.isBefore(Instant.EPOCH)||notAfter.getEpochSecond()>253402300799L||notAfter.getNano()%1000!=0)throw invalid();}
        @Override public String toString(){return "KycProviderInitiation";}
    }
    record Recovery(UUID actionId,Scope scope,String providerOperationId) {
        public Recovery {identity(actionId,scope);operation(providerOperationId);}
        @Override public String toString(){return "KycProviderRecovery";}
    }
    record Outcome(UUID actionId,Scope scope,State state,Execution execution,String providerOperationId,String receiptDigest) {
        public Outcome {
            identity(actionId,scope);Objects.requireNonNull(state);Objects.requireNonNull(execution);operation(providerOperationId);if(receiptDigest!=null)digest(receiptDigest);
            if(state==State.NOT_CONFIGURED&&(execution==Execution.EXECUTED||receiptDigest!=null)||state==State.UNKNOWN&&execution!=Execution.UNKNOWN)throw invalid();
            if(state==State.PENDING||state==State.VERIFIED||state==State.REJECTED){if(execution!=Execution.EXECUTED||providerOperationId==null||state!=State.PENDING&&receiptDigest==null)throw invalid();}
            if(execution==Execution.NOT_EXECUTED&&(providerOperationId!=null||receiptDigest!=null))throw invalid();
        }
        @Override public String toString(){return "KycProviderOutcome";}
    }
    boolean configured();
    Mono<Outcome> initiate(Initiation request);
    Mono<Outcome> observe(Recovery request);
    private static void identity(UUID action,Scope scope){if(action==null||action.equals(new UUID(0,0))||scope==null)throw invalid();}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void operation(String value){if(value!=null&&(value.isBlank()||!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>512||value.codePoints().anyMatch(Character::isISOControl)))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_KYC_PROVIDER_BOUNDARY_DATA");}
}
