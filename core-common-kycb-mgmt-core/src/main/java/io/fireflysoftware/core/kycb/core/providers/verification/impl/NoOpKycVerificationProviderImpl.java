/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.providers.verification.impl;

import io.fireflysoftware.core.kycb.core.providers.verification.KycVerificationProvider;
import java.util.Objects;
import reactor.core.publisher.Mono;

public final class NoOpKycVerificationProviderImpl implements KycVerificationProvider {
    @Override public boolean configured(){return false;}
    @Override public Mono<Outcome> initiate(Initiation request){return Mono.defer(()->{Objects.requireNonNull(request);return Mono.just(new Outcome(request.actionId(),request.scope(),State.NOT_CONFIGURED,Execution.NOT_EXECUTED,null,null));});}
    @Override public Mono<Outcome> observe(Recovery request){return Mono.defer(()->{Objects.requireNonNull(request);return Mono.just(new Outcome(request.actionId(),request.scope(),State.NOT_CONFIGURED,Execution.UNKNOWN,request.providerOperationId(),null));});}
    @Override public String toString(){return "NoOpKycVerificationProviderImpl";}
}
