package io.fireflysoftware.core.kycb.core.providers.verification;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.core.providers.verification.KycVerificationProvider.*;
import io.fireflysoftware.core.kycb.core.providers.verification.impl.NoOpKycVerificationProviderImpl;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.Scope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KycVerificationNoOpTest {
    private final Scope scope=new Scope(new UUID(0,1),new UUID(0,2),new UUID(0,3));
    private final UUID action=new UUID(0,4);
    @Test void unconfiguredInitiationTruthfullyReportsNoExecutionAndNoProviderReceipt(){var result=new NoOpKycVerificationProviderImpl().initiate(new Initiation(action,scope,"a".repeat(64),Instant.parse("2026-11-02T12:00:05Z"))).block();assertNotNull(result);assertEquals(State.NOT_CONFIGURED,result.state());assertEquals(Execution.NOT_EXECUTED,result.execution());assertNull(result.providerOperationId());assertNull(result.receiptDigest());assertEquals(action,result.actionId());assertEquals(scope,result.scope());}
    @Test void unavailableRecoveryCannotAssertNoPriorEffectOrInventAbsence(){var result=new NoOpKycVerificationProviderImpl().observe(new Recovery(action,scope,"retained-external-operation")).block();assertNotNull(result);assertEquals(State.NOT_CONFIGURED,result.state());assertEquals(Execution.UNKNOWN,result.execution());assertEquals("retained-external-operation",result.providerOperationId());assertNull(result.receiptDigest());assertEquals(action,result.actionId());}
    @Test void unknownRecoveryWithoutOperationStillRetainsOnlyOriginalOwnerAction(){var result=new NoOpKycVerificationProviderImpl().observe(new Recovery(action,scope,null)).block();assertEquals(Execution.UNKNOWN,result.execution());assertNull(result.providerOperationId());assertEquals(action,result.actionId());}
    @Test void noConfiguredAdapterCannotAdvertiseAvailability(){assertFalse(new NoOpKycVerificationProviderImpl().configured());}
}
