package io.fireflysoftware.core.kycb.models.repositories.decision;

import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.*;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface KycDecisionStore {
    Mono<Case> readGuardedCase(Scope scope);
    Mono<Document> readGuardedDocument(Scope scope,UUID documentId);
    Mono<Control> control(Scope scope);
    Mono<Control> insert(Control row);
    Mono<Control> transition(Control previous,Control next);
    Mono<Action> action(Scope scope,UUID actionId);
    Mono<Action> append(Action row);
    Flux<Evidence> evidence(Scope scope,UUID submissionActionId);
    Mono<Evidence> append(Evidence row);
    Mono<Audit> audit(Scope scope,UUID actionId);
    Mono<Audit> append(Audit row);
}
