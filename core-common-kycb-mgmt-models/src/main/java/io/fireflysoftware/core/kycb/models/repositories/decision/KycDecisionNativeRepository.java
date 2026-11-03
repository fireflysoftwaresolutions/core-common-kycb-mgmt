package io.fireflysoftware.core.kycb.models.repositories.decision;

import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Control;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

/** Technical current scope, shared row locks and completed-audit pairing; ordinary business CRUD stays mapped. */
public interface KycDecisionNativeRepository extends Repository<Control,UUID>,KycDecisionPersistence {
    record Stamp(UUID contextNonce,Instant databaseTime,Instant commandUntil) {}
    @Query("SELECT * FROM public.kyc_manual_enter(:nonce,:tenant,:party,:caseId,:action,:selection,:purpose,:intent,:version,:documents,:issuer,:subject,:client,:platform,:realm,:staff,:policy,:sourceExpiryZone,:authorityUntil,:commandUntil)")
    Mono<Stamp> enter(@Param("nonce") UUID nonce,@Param("tenant") UUID tenant,@Param("party") UUID party,@Param("caseId") UUID caseId,
        @Param("action") UUID action,@Param("selection") UUID selection,@Param("purpose") String purpose,@Param("intent") String intent,@Param("version") Long version,
        @Param("documents") UUID[] documents,@Param("issuer") String issuer,@Param("subject") String subject,@Param("client") String client,@Param("platform") String platform,
        @Param("realm") String realm,@Param("staff") UUID staff,@Param("policy") String policy,@Param("sourceExpiryZone") String sourceExpiryZone,@Param("authorityUntil") OffsetDateTime authorityUntil,@Param("commandUntil") OffsetDateTime commandUntil);
    @Query("SELECT public.kyc_manual_pair_audit(:nonce,:auditNonce,:event,:sequence,:hash,:replay)")
    Mono<UUID> pairAudit(@Param("nonce") UUID nonce,@Param("auditNonce") UUID auditNonce,@Param("event") UUID event,@Param("sequence") long sequence,@Param("hash") String hash,@Param("replay") boolean replay);
    @Query("SELECT public.kyc_manual_complete(:nonce)")
    Mono<UUID> complete(@Param("nonce") UUID nonce);
}
