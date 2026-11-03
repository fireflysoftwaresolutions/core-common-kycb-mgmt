/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.models.entities.decision;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Assigned immutable identifiers and explicit server times; no legacy VERIFIED admission. */
public final class KycDecisionRows {
    private KycDecisionRows() {}
    @Table("kyc_manual_case_source")
    public record Case(@Id UUID kycVerificationId,UUID partyId) {}
    @Table("kyc_manual_document_source")
    public record Document(@Id UUID verificationDocumentId,UUID kycVerificationId,UUID identityDocumentId,
            String documentType,String verificationPurpose,String documentReference,String documentSystemId,LocalDateTime expiryDate) {
        @Override public String toString(){return "KycDecisionDocument";}
    }
    @Table("kyc_manual_control")
    public record Control(@Id UUID kycCaseId,UUID tenantId,UUID partyId,UUID submissionActionId,UUID reviewActionId,String state,long version) {}
    @Table("kyc_manual_action")
    public record Action(@Id UUID actionId,UUID tenantId,UUID partyId,UUID kycCaseId,UUID receiptId,UUID eventId,
            String operation,UUID selectionId,String intentDigest,String payload,String payloadDigest,String resultingState,long resultingVersion,Instant recordedAt,
            String actorIssuer,String actorSubject,String actorClientId,String platformTenantId,String staffRealm,UUID staffActorId,String policyRevision) {
        @Override public String toString(){return "KycManualAction";}
    }
    @Table("kyc_manual_evidence")
    public record Evidence(@Id UUID bindingId,UUID tenantId,UUID partyId,UUID kycCaseId,UUID submissionActionId,UUID documentId,
            UUID identityDocumentId,String documentType,String verificationPurpose,String documentReference,String documentSystemId,LocalDateTime sourceExpiryDate,
            String sourceExpiryZone,Integer sourceExpiryOffsetSeconds,Instant sourceExpiresAt,String contentDigest,Instant collectedAt,Instant validUntil) {
        @Override public String toString(){return "KycManualEvidenceBinding";}
    }
    @Table("kyc_manual_audit")
    public record Audit(@Id UUID actionId,UUID tenantId,UUID kycCaseId,UUID eventId,UUID auditContextNonce,long auditSequence,String auditRecordHash) {}
}
