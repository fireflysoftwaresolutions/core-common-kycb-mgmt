/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Pure transitions after genuine owner, evidence, staff, and current policy observations. */
public final class KycManualDecisionRules {
    private KycManualDecisionRules() {}
    public static Submission submit(UUID actionId,Scope scope,Actor maker,List<Evidence> evidence,List<SourceExpiry> sourceExpiries,
            Instant now,Instant validUntil,Instant ownerPolicyUntil) {
        if(evidence==null||evidence.isEmpty()||evidence.size()>32||now==null||validUntil==null||ownerPolicyUntil==null
                ||!validUntil.isAfter(now)||validUntil.isAfter(ownerPolicyUntil))throw invalid();
        var ids=new HashSet<UUID>();var sorted=new ArrayList<Evidence>();
        for(var item:evidence){if(item==null||!ids.add(item.documentId())||item.collectedAt().isAfter(now)||!now.isBefore(item.validUntil())||validUntil.isAfter(item.validUntil()))throw invalid();sorted.add(item);}
        sorted.sort(Comparator.comparing(item->item.documentId().toString()));
        var expiry=orderedExpiries(sorted,sourceExpiries,now);
        return new Submission(actionId,scope,maker,sorted,expiry,now,validUntil,digest(actionId,scope,maker,sorted,expiry,now,validUntil),Purpose.CARD_ENROLLMENT,DecisionMode.MANUAL_REVIEW,ExternalExecution.NOT_REQUESTED);
    }
    public static Review review(UUID actionId,Submission submission,Actor reviewer,ReviewOutcome outcome,String reasonCode,Instant now) {
        requireSubmission(submission);if(reviewer==null||now==null||now.isBefore(submission.submittedAt())||!now.isBefore(submission.validUntil()))throw invalid();
        independent(submission.maker(),reviewer);return new Review(actionId,submission.digest(),reviewer,outcome,reasonCode,now);
    }
    public static CurrentDecision current(Submission submission,Review review,boolean revoked,long controlVersion,Instant now,Instant effectiveUntil) {
        requireSubmission(submission);
        if(review==null||revoked||controlVersion<0||review.outcome()!=ReviewOutcome.APPROVE||!submission.digest().equals(review.submissionDigest())
                ||now==null||now.isBefore(review.reviewedAt())||review.reviewedAt().isBefore(submission.submittedAt())
                ||!now.isBefore(submission.validUntil())||effectiveUntil==null||!effectiveUntil.isAfter(now)
                ||effectiveUntil.isAfter(now.plusSeconds(5))||effectiveUntil.isAfter(submission.validUntil()))throw invalid();
        independent(submission.maker(),review.reviewer());return new CurrentDecision(submission.scope(),submission.actionId(),review.actionId(),submission.digest(),controlVersion,Purpose.CARD_ENROLLMENT,DecisionMode.MANUAL_REVIEW,ExternalExecution.NOT_REQUESTED,submission.validUntil(),now,effectiveUntil);
    }
    public static void requireSubmission(Submission value){
        if(value==null||!value.digest().equals(digest(value.actionId(),value.scope(),value.maker(),value.evidence(),value.sourceExpiries(),value.submittedAt(),value.validUntil())))throw invalid();
        if(!orderedExpiries(value.evidence(),value.sourceExpiries(),value.submittedAt()).equals(value.sourceExpiries()))throw invalid();
        var ids=new HashSet<UUID>();UUID previous=null;
        for(var item:value.evidence()){
            if(!ids.add(item.documentId())||item.collectedAt().isAfter(value.submittedAt())||value.validUntil().isAfter(item.validUntil())
                    ||previous!=null&&previous.toString().compareTo(item.documentId().toString())>=0)throw invalid();previous=item.documentId();
        }
    }
    private static List<SourceExpiry> orderedExpiries(List<Evidence> evidence,List<SourceExpiry> sourceExpiries,Instant at){
        if(sourceExpiries==null||sourceExpiries.size()!=evidence.size())throw invalid();
        var rows=new HashMap<UUID,SourceExpiry>();for(var row:sourceExpiries)if(row==null||rows.putIfAbsent(row.documentId(),row)!=null)throw invalid();
        var ordered=new ArrayList<SourceExpiry>();
        for(var item:evidence){var row=rows.remove(item.documentId());if(row==null)throw invalid();
            KycSourceExpiryRules.requireRetained(row.sourceDate(),row.zoneId(),row.offsetSeconds(),row.expiresAt(),at,item.validUntil());ordered.add(row);}
        return List.copyOf(ordered);
    }
    private static void independent(Actor maker,Actor reviewer){if(maker.staffActorId().equals(reviewer.staffActorId())||maker.issuer().equals(reviewer.issuer())&&maker.subject().equals(reviewer.subject()))throw invalid();}
    private static String digest(UUID action,Scope scope,Actor maker,List<Evidence> evidence,List<SourceExpiry> sourceExpiries,Instant submittedAt,Instant until){
        if(action==null||scope==null||maker==null||submittedAt==null||until==null)throw invalid();
        var values=new ArrayList<String>();Collections.addAll(values,"kyc-manual-submission-expiry-bound-1","CARD_ENROLLMENT",action.toString(),scope.tenantId().toString(),scope.partyId().toString(),scope.kycCaseId().toString(),maker.issuer(),maker.subject(),maker.staffActorId().toString(),submittedAt.toString(),until.toString(),Integer.toString(evidence.size()));
        for(var item:evidence)Collections.addAll(values,item.documentId().toString(),item.sourceSystem(),item.sourceReference(),item.contentDigest(),item.collectedAt().toString(),item.validUntil().toString());
        for(var source:sourceExpiries)Collections.addAll(values,source.documentId().toString(),source.sourceDate()==null?"UNDATED":"DATED",
                source.sourceDate()==null?"":source.sourceDate().toString(),source.zoneId()==null?"":source.zoneId(),
                source.offsetSeconds()==null?"":source.offsetSeconds().toString(),source.expiresAt()==null?"":source.expiresAt().toString());
        try{var hash=MessageDigest.getInstance("SHA-256");for(var value:values){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));hash.update((byte)':');hash.update(bytes);}return HexFormat.of().formatHex(hash.digest());}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA256_REQUIRED",unavailable);}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("KYC_MANUAL_DECISION_NOT_ADMITTED");}
}
