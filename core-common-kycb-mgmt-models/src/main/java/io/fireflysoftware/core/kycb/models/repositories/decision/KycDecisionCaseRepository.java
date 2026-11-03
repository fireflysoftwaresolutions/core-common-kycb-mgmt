package io.fireflysoftware.core.kycb.models.repositories.decision;

import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Case;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Mono;

public interface KycDecisionCaseRepository extends Repository<Case,UUID>,KycDecisionPersistence {
    Mono<Case> findByKycVerificationIdAndPartyId(UUID kycCaseId,UUID partyId);
}
