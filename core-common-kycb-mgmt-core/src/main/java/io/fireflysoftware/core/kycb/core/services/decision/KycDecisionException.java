package io.fireflysoftware.core.kycb.core.services.decision;

import java.util.UUID;
import org.fireflyframework.core.transaction.*;

public final class KycDecisionException extends RuntimeException {
    public enum Reason { REFUSED, CONFLICT, NOT_FOUND, UNAVAILABLE, UNKNOWN, WITHHELD }
    private final UUID actionId;private final Reason reason;private final TransactionFacts facts;private final CleanupFact cleanup;
    public KycDecisionException(UUID actionId,Reason reason,TransactionFacts facts,CleanupFact cleanup){super("KYC_MANUAL_"+reason);this.actionId=actionId;this.reason=reason;this.facts=facts;this.cleanup=cleanup;}
    public UUID actionId(){return actionId;}public Reason reason(){return reason;}public TransactionFacts transactionFacts(){return facts;}public CleanupFact cleanupFact(){return cleanup;}
}
