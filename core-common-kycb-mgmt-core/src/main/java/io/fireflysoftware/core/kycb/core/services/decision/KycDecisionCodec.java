/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.kycb.core.services.decision;

import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows.Action;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.fireflyframework.kernel.audit.*;

/** Immutable owning journal encoding; neither legacy verification flags nor provider outcomes enter admission. */
public final class KycDecisionCodec {
    private static final ObjectMapper JSON=JsonMapper.builder().addModule(new JavaTimeModule())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private KycDecisionCodec() {}
    public static String encode(Object value){try{return JSON.writeValueAsString(Objects.requireNonNull(value));}catch(IOException failure){throw invalid();}}
    public static <T> T decode(String value,Class<T> type){try{if(value==null||value.length()>262144)throw invalid();T decoded=JSON.readValue(value,type);if(!encode(decoded).equals(value))throw invalid();return decoded;}catch(IOException failure){throw invalid();}}
    public static String payloadDigest(String value){if(value==null)throw invalid();return digest(value.getBytes(StandardCharsets.UTF_8));}
    public static String intent(Scope scope,Actor actor,CommandKind kind,Object request){
        Object normalized=request;
        if(kind==CommandKind.SUBMIT){if(!(request instanceof SubmitRequest value))throw invalid();var evidence=new ArrayList<>(value.evidence());evidence.sort(Comparator.comparing(row->row.documentId().toString()));normalized=new SubmitRequest(value.actionId(),value.expectedControlVersion(),evidence,value.validUntil());}
        else if(kind==CommandKind.REVIEW&&!(request instanceof ReviewRequest)||kind==CommandKind.REVOKE&&!(request instanceof RevokeRequest))throw invalid();
        return payloadDigest(encode(List.of("kyc-manual-intent-1","CARD_ENROLLMENT",scope,actor,kind,normalized)));
    }
    public static ActionReceipt receipt(Action row){return new ActionReceipt(new Scope(row.tenantId(),row.partyId(),row.kycCaseId()),row.actionId(),row.receiptId(),row.eventId(),row.selectionId(),row.operation().equals("SUBMIT")?decode(row.payload(),Submission.class).digest():row.operation().equals("REVIEW")?decode(row.payload(),Review.class).submissionDigest():null,CommandKind.valueOf(row.operation()),State.valueOf(row.resultingState()),row.resultingVersion(),row.recordedAt());}
    public static AuditEvent event(Action action){
        if(!payloadDigest(action.payload()).equals(action.payloadDigest()))throw invalid();
        return new AuditEvent(action.eventId(),new AuditStream(action.tenantId(),"core-common-kycb-mgmt","kyc-manual-decision",action.kycCaseId()),
            new AuditActor(action.actorIssuer(),action.actorSubject(),action.actorClientId(),action.platformTenantId(),action.staffRealm(),action.staffActorId()),
            "KYC_MANUAL_"+CommandKind.valueOf(action.operation()).name(),AuditEvent.Outcome.COMPLETED,action.actionId(),action.receiptId(),action.actionId(),action.recordedAt(),auditDigest(action),action.policyRevision());
    }
    public static String auditDigest(Action action){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);text(out,"kyc.manual.decision.evidence");out.writeInt(1);
            for(UUID id:List.of(action.tenantId(),action.partyId(),action.kycCaseId(),action.actionId(),action.receiptId(),action.eventId(),action.selectionId(),action.staffActorId())){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
            out.writeLong(action.resultingVersion());out.writeLong(action.recordedAt().getEpochSecond());out.writeInt(action.recordedAt().getNano());
            for(String value:List.of(action.operation(),action.intentDigest(),action.payloadDigest(),action.resultingState(),action.actorIssuer(),action.actorSubject(),action.actorClientId(),action.platformTenantId(),action.staffRealm(),action.policyRevision()))text(out,value);
            return digest(bytes.toByteArray());
        }catch(IOException failure){throw invalid();}
    }
    private static String digest(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("SHA256_REQUIRED",failure);}}
    private static void text(DataOutputStream output,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("EXACT_ORIGINAL_KYC_MANUAL_JOURNAL_REQUIRED");}
}
