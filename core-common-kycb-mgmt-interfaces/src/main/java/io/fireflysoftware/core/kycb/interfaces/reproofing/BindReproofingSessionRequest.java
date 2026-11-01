package io.fireflysoftware.core.kycb.interfaces.reproofing;

public record BindReproofingSessionRequest(ReproofingBinding binding, String providerSessionId) {
    @Override
    public String toString() {
        return "BindReproofingSessionRequest[REDACTED]";
    }
}
