package io.fireflysoftware.core.kycb.core.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.*;

import reactor.core.publisher.Mono;

public interface ReproofingEvidencePort {
    Mono<VerifiedReproofingEvidence> verify(ReproofingEvidenceRequest request);
}
