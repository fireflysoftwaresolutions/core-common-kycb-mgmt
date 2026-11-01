package io.fireflysoftware.core.kycb.core.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.*;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReproofingService {
    Mono<ReproofingCaseResponse> create(CreateReproofingCaseRequest request);

    Mono<ReproofingCaseResponse> inspect(UUID id, ConfirmReproofingRequest request);

    Mono<ReproofingCaseResponse> bindSession(UUID id, BindReproofingSessionRequest request);

    Mono<ReproofingCaseResponse> confirm(UUID id, ConfirmReproofingRequest request);

    Mono<ReproofingAuthorization> consume(UUID id, ReproofingAuthorizationRequest request);

    Mono<ReproofingAuthorization> verify(UUID id, ReproofingAuthorizationRequest request);

    Mono<ReproofingCaseResponse> record(UUID id, RecordReproofingExecutionRequest request);
}
