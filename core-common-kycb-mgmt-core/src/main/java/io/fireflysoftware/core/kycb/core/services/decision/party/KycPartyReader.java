package io.fireflysoftware.core.kycb.core.services.decision.party;

import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.Permit;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface KycPartyReader {
    /** Exact persisted Customer identity only; never a KYC, ACTIVE, or eligibility decision. */
    record Observation(UUID tenantId,UUID partyId,String partyKind,Instant observedAt,Instant effectiveUntil) {
        public Observation {if(tenantId==null||partyId==null||partyKind==null||observedAt==null||effectiveUntil==null||!effectiveUntil.isAfter(observedAt)||effectiveUntil.isAfter(observedAt.plusSeconds(5)))throw new IllegalArgumentException("CURRENT_CUSTOMER_IDENTITY_REQUIRED");}
        @Override public String toString(){return "KycCustomerObservation";}
    }
    Mono<Observation> observe(Permit permit);
}
