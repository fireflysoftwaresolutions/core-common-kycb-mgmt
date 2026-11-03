package io.fireflysoftware.core.kycb.core.services.decision;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface KycDecisionService {
    Mono<ActionReceipt> submit(Scope scope,SubmitRequest request);
    Mono<ActionReceipt> review(Scope scope,ReviewRequest request);
    Mono<ActionReceipt> revoke(Scope scope,RevokeRequest request);
    Mono<ActionReceipt> recover(Scope scope,UUID originalActionId);
    Mono<CurrentDecision> current(Scope scope,UUID originalDecisionActionId);
}
