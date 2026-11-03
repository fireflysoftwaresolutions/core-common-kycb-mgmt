package io.fireflysoftware.core.kycb.core.services.decision.party;

/** Ephemeral verified inbound bearer, carried only in the Reactor request context. */
public final class KycCallerToken {
    private final String value;
    public KycCallerToken(String value){if(value==null||value.isBlank()||value.length()>16384||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("ORIGINAL_KYC_CALLER_REQUIRED");this.value=value;}
    public String value(){return value;}
    @Override public String toString(){return "KycCallerToken[redacted]";}
}
