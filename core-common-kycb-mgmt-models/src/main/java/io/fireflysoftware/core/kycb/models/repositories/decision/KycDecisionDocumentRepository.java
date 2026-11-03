package io.fireflysoftware.core.kycb.models.repositories.decision;

import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Document;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Mono;

public interface KycDecisionDocumentRepository extends Repository<Document,UUID>,KycDecisionPersistence {
    Mono<Document> findByKycVerificationIdAndVerificationDocumentId(UUID kycCaseId,UUID documentId);
}
