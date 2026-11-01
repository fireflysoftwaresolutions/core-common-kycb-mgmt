package io.fireflysoftware.core.kycb.core.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.*;

import reactor.core.publisher.Mono;

public interface ReproofingNativePort {
    Mono<ReproofingAdmission> prepare(ReproofingNativeRequest request);

    Mono<ReproofingAdmission> admit(ReproofingNativeRequest request);

    Mono<ReproofingExecution> journal(ReproofingNativeRequest request);
}
