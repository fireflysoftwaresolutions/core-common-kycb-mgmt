package io.fireflysoftware.core.kycb.core.services.decision;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class KycSourceExpiryRulesTest {
    static final Instant OBSERVED=Instant.parse("2026-01-01T00:00:00Z");
    @Test void ownerZoneIsRequiredAndDifferentExplicitZonesCannotBeInferred(){
        var civil=LocalDateTime.parse("2027-01-01T12:00:00");
        assertThrows(NullPointerException.class,()->KycSourceExpiryRules.resolve(civil,null,OBSERVED,OBSERVED.plusSeconds(1)));
        var utc=KycSourceExpiryRules.resolve(civil,ZoneId.of("UTC"),OBSERVED,OBSERVED.plusSeconds(1));
        var ny=KycSourceExpiryRules.resolve(civil,ZoneId.of("America/New_York"),OBSERVED,OBSERVED.plusSeconds(1));
        assertEquals(Instant.parse("2027-01-01T12:00:00Z"),utc.expiresAt());assertEquals(Instant.parse("2027-01-01T17:00:00Z"),ny.expiresAt());assertEquals(-18000,ny.offsetSeconds());
    }
    @Test void daylightSavingGapAndOverlapAreBothRefused(){
        for(var civil:new LocalDateTime[]{LocalDateTime.parse("2027-03-14T02:30:00"),LocalDateTime.parse("2027-11-07T01:30:00")})assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.resolve(civil,ZoneId.of("America/New_York"),OBSERVED,OBSERVED.plusSeconds(1)));
        var valid=KycSourceExpiryRules.resolve(LocalDateTime.parse("2027-03-14T03:30:00"),ZoneId.of("America/New_York"),OBSERVED,OBSERVED.plusSeconds(1));assertEquals(Instant.parse("2027-03-14T07:30:00Z"),valid.expiresAt());
    }
    @Test void exactExpiryCapsEvidenceAndExpiredSourceCannotBeAdmitted(){
        var civil=LocalDateTime.parse("2027-01-01T00:00:00");var expiry=Instant.parse("2027-01-01T00:00:00Z");
        assertEquals(expiry,KycSourceExpiryRules.resolve(civil,ZoneId.of("UTC"),OBSERVED,expiry).expiresAt());
        assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.resolve(civil,ZoneId.of("UTC"),OBSERVED,expiry.plusNanos(1000)));
        assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.resolve(civil,ZoneId.of("UTC"),expiry,expiry));
    }
    @Test void retainedInterpretationCannotBeExtendedByChangingOffsetOrResolvedInstant(){
        var civil=LocalDateTime.parse("2027-01-01T00:00:00");var expiry=Instant.parse("2027-01-01T00:00:00Z");
        assertDoesNotThrow(()->KycSourceExpiryRules.requireRetained(civil,"UTC",0,expiry,OBSERVED,expiry));
        assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.requireRetained(civil,"UTC",-3600,expiry.plusSeconds(3600),OBSERVED,expiry));
        assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.requireRetained(civil,"UTC",0,expiry.plusSeconds(1),OBSERVED,expiry));
    }
    @Test void undatedSourceDoesNotAcquireAnInventedExpiry(){
        assertNull(KycSourceExpiryRules.resolve(null,ZoneId.of("UTC"),OBSERVED,OBSERVED.plusSeconds(1)));
        assertDoesNotThrow(()->KycSourceExpiryRules.requireRetained(null,null,null,null,OBSERVED,OBSERVED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->KycSourceExpiryRules.requireRetained(null,"UTC",0,OBSERVED,OBSERVED,OBSERVED.plusSeconds(1)));
    }
}
