package io.fireflysoftware.core.kycb.interfaces.reproofing;

import java.util.UUID;

public record ReproofingBinding(String issuer, String realm, String tenant, String subject, UUID partyId, String population, String purpose, String generation, String credentialRevision, String nonce) {
    @Override public String toString() { return "ReproofingBinding[REDACTED]"; }
}
