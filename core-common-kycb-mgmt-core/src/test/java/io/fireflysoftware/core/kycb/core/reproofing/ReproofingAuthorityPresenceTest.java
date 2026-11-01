package io.fireflysoftware.core.kycb.core.reproofing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReproofingAuthorityPresenceTest {
    @Test void durableAuthorityIsImplementedInsteadOfCrudApproval() {
        assertDoesNotThrow(()  -> Class.forName("io.fireflysoftware.core.kycb.core.reproofing.impl.ReproofingServiceImpl"), "Credential-loss recovery requires a dedicated durable authority; KYC CRUD is not approval");
    }
}
