/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.interfaces.decision;

import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/** Data contracts alone are not evidence of current owner authority or provider execution. */
public final class KycDecisionContracts {
    private KycDecisionContracts() {}
    public enum Purpose { CARD_ENROLLMENT }
    public enum DecisionMode { MANUAL_REVIEW }
    public enum ExternalExecution { NOT_REQUESTED }
    public enum ReviewOutcome { APPROVE, REJECT }
    @Schema(name="KycDecisionScope")
    public record Scope(UUID tenantId,UUID partyId,UUID kycCaseId) {
        public Scope {uuid(tenantId);uuid(partyId);uuid(kycCaseId);}
        @Override public String toString(){return "KycDecisionScope";}
    }
    @Schema(name="KycDecisionActor")
    public record Actor(String issuer,String subject,UUID staffActorId) {
        public Actor {text(issuer,512);text(subject,512);uuid(staffActorId);}
        @Override public String toString(){return "KycDecisionActor";}
    }
    /** The signed staff member attests this provenance; it is not an external provider verification. */
    @Schema(name="KycManualEvidence")
    public record Evidence(UUID documentId,String sourceSystem,String sourceReference,String contentDigest,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant collectedAt,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant validUntil) {
        public Evidence {uuid(documentId);text(sourceSystem,128);text(sourceReference,2048);digest(contentDigest);instant(collectedAt);instant(validUntil);if(!validUntil.isAfter(collectedAt))throw invalid();}
        @Override public String toString(){return "KycManualEvidence";}
    }
    /** Internal persisted interpretation from the owner policy, never an incoming caller field. */
    public record SourceExpiry(UUID documentId,LocalDateTime sourceDate,String zoneId,Integer offsetSeconds,Instant expiresAt) {
        public SourceExpiry {uuid(documentId);if(sourceDate==null){if(zoneId!=null||offsetSeconds!=null||expiresAt!=null)throw invalid();}
            else {text(zoneId,128);if(offsetSeconds==null||offsetSeconds< -64800||offsetSeconds>64800||sourceDate.getNano()%1000!=0)throw invalid();instant(expiresAt);}}
    }
    @Schema(name="KycManualSubmission")
    public record Submission(UUID actionId,Scope scope,Actor maker,List<Evidence> evidence,List<SourceExpiry> sourceExpiries,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant submittedAt,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant validUntil,String digest,
            Purpose purpose,DecisionMode mode,ExternalExecution externalExecution) {
        public Submission {uuid(actionId);Objects.requireNonNull(scope);Objects.requireNonNull(maker);evidence=List.copyOf(evidence);sourceExpiries=List.copyOf(sourceExpiries);if(evidence.isEmpty()||evidence.size()>32||sourceExpiries.size()!=evidence.size())throw invalid();instant(submittedAt);instant(validUntil);KycDecisionContracts.digest(digest);if(purpose!=Purpose.CARD_ENROLLMENT||mode!=DecisionMode.MANUAL_REVIEW||externalExecution!=ExternalExecution.NOT_REQUESTED||!validUntil.isAfter(submittedAt))throw invalid();}
        @Override public String toString(){return "KycManualSubmission";}
    }
    @Schema(name="KycManualReview")
    public record Review(UUID actionId,String submissionDigest,Actor reviewer,ReviewOutcome outcome,String reasonCode,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant reviewedAt) {
        public Review {uuid(actionId);digest(submissionDigest);Objects.requireNonNull(reviewer);Objects.requireNonNull(outcome);if(reasonCode==null||!reasonCode.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();instant(reviewedAt);}
        @Override public String toString(){return "KycManualReview";}
    }
    @Schema(name="KycManualCurrentDecision")
    public record CurrentDecision(Scope scope,UUID submissionActionId,UUID decisionActionId,String submissionDigest,
            long controlVersion,Purpose purpose,DecisionMode mode,ExternalExecution externalExecution,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant businessValidUntil,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant observedAt,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant effectiveUntil) {
        public CurrentDecision {Objects.requireNonNull(scope);uuid(submissionActionId);uuid(decisionActionId);digest(submissionDigest);if(controlVersion<0||purpose!=Purpose.CARD_ENROLLMENT||mode!=DecisionMode.MANUAL_REVIEW||externalExecution!=ExternalExecution.NOT_REQUESTED)throw invalid();instant(businessValidUntil);instant(observedAt);instant(effectiveUntil);if(!effectiveUntil.isAfter(observedAt)||effectiveUntil.isAfter(observedAt.plusSeconds(5))||effectiveUntil.isAfter(businessValidUntil))throw invalid();}
        @Override public String toString(){return "KycManualCurrentDecision";}
    }
    public enum State { SUBMITTED, APPROVED, REJECTED, REVOKED }
    public enum CommandKind { SUBMIT, REVIEW, REVOKE }
    @Schema(name="KycManualSubmitRequest")
    public record SubmitRequest(UUID actionId,long expectedControlVersion,List<Evidence> evidence,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant validUntil) {
        public SubmitRequest {uuid(actionId);if(expectedControlVersion< -1)throw invalid();evidence=List.copyOf(evidence);if(evidence.isEmpty()||evidence.size()>32)throw invalid();instant(validUntil);}
        @Override public String toString(){return "KycManualSubmitRequest";}
    }
    @Schema(name="KycManualReviewRequest")
    public record ReviewRequest(UUID actionId,UUID submissionActionId,String submissionDigest,long expectedControlVersion,ReviewOutcome outcome,String reasonCode) {
        public ReviewRequest {uuid(actionId);uuid(submissionActionId);digest(submissionDigest);if(expectedControlVersion<0)throw invalid();Objects.requireNonNull(outcome);reason(reasonCode);}
        @Override public String toString(){return "KycManualReviewRequest";}
    }
    @Schema(name="KycManualRevokeRequest")
    public record RevokeRequest(UUID actionId,UUID selectedActionId,long expectedControlVersion,String reasonCode) {
        public RevokeRequest {uuid(actionId);uuid(selectedActionId);if(expectedControlVersion<0)throw invalid();reason(reasonCode);}
        @Override public String toString(){return "KycManualRevokeRequest";}
    }
    @Schema(name="KycManualActionReceipt")
    public record ActionReceipt(Scope scope,UUID actionId,UUID receiptId,UUID eventId,UUID selectionActionId,String submissionDigest,CommandKind command,State resultingState,long resultingControlVersion,
            @Schema(ref="#/components/schemas/FireflyInstant") Instant recordedAt) {
        public ActionReceipt {Objects.requireNonNull(scope);uuid(actionId);uuid(receiptId);uuid(eventId);uuid(selectionActionId);Objects.requireNonNull(command);if(command!=CommandKind.REVOKE)digest(submissionDigest);else if(submissionDigest!=null)throw invalid();Objects.requireNonNull(resultingState);if(resultingControlVersion<0)throw invalid();instant(recordedAt);}
        @Override public String toString(){return "KycManualActionReceipt";}
    }
    private static void reason(String value){if(value==null||!value.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();}
    private static void uuid(UUID value){if(value==null||value.equals(new UUID(0,0)))throw invalid();}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void instant(Instant value){if(value==null||value.isBefore(Instant.EPOCH)||value.getEpochSecond()>253402300799L||value.getNano()%1000!=0)throw invalid();}
    private static void text(String value,int max){if(value==null||value.isBlank()||!StandardCharsets.UTF_8.newEncoder().canEncode(value)||value.getBytes(StandardCharsets.UTF_8).length>max||value.codePoints().anyMatch(Character::isISOControl))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_KYC_DECISION_DATA");}
}
