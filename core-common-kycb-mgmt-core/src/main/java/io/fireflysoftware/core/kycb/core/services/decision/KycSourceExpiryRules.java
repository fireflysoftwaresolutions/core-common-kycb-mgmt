/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision;

import java.time.*;
import java.util.Objects;

/** Civil source dates require an explicit owner zone and one unambiguous offset. */
public final class KycSourceExpiryRules {
    private KycSourceExpiryRules() {}
    public record Resolution(String zoneId,int offsetSeconds,Instant expiresAt) {}
    public static Resolution resolve(LocalDateTime source,ZoneId zone,Instant observedAt,Instant evidenceUntil) {
        Objects.requireNonNull(zone);Objects.requireNonNull(observedAt);Objects.requireNonNull(evidenceUntil);
        if(source==null)return null;
        var offsets=zone.getRules().getValidOffsets(source);
        if(offsets.size()!=1||source.getNano()%1000!=0)throw invalid();
        var offset=offsets.getFirst();Instant expiry=source.toInstant(offset);
        if(!observedAt.isBefore(expiry)||evidenceUntil.isAfter(expiry))throw invalid();
        return new Resolution(zone.getId(),offset.getTotalSeconds(),expiry);
    }
    public static void requireRetained(LocalDateTime source,String zone,Integer offset,Instant expiry,Instant observedAt,Instant evidenceUntil) {
        if(source==null){if(zone!=null||offset!=null||expiry!=null)throw invalid();return;}
        if(zone==null||offset==null||expiry==null)throw invalid();
        if(!new Resolution(zone,offset,expiry).equals(resolve(source,ZoneId.of(zone),observedAt,evidenceUntil)))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("EXPLICIT_CURRENT_SOURCE_DOCUMENT_EXPIRY_REQUIRED");}
}
