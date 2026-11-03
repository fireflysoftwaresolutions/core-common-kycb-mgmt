package io.fireflysoftware.core.kycb.core.services.decision.store;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.core.kycb.core.services.decision.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.*;
import io.fireflysoftware.core.kycb.core.services.decision.impl.CurrentKycDecisionAuthorityImpl.*;
import io.fireflysoftware.core.kycb.core.services.decision.party.KycPartyReader;
import io.fireflysoftware.core.kycb.interfaces.decision.KycDecisionContracts.*;
import io.fireflysoftware.core.kycb.models.entities.decision.KycDecisionRows;
import io.r2dbc.spi.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Actual owning PostgreSQL/canonical audit; native principal/PDP and Customer facts are explicit controlled fixtures. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KycManualDecisionNativeIT {
    static String host,database,user,password;static int port;
    static final String writerPassword=UUID.randomUUID().toString(),readerPassword=UUID.randomUUID().toString();
    static final Duration LIMIT=Duration.ofSeconds(10);
    @BeforeAll static void install()throws Exception{
        Path path=Path.of(Objects.requireNonNull(System.getProperty("kyc.manual.test.database-profile")));
        assertEquals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),Files.getPosixFilePermissions(path));
        var profile=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(path));assertEquals("isolated-owned-kyc-manual-native",profile.get("purpose").asText());host=profile.get("host").asText();assertEquals("127.0.0.1",host);port=profile.get("port").asInt();database=profile.get("database").asText();assertTrue(database.startsWith("kyc_manual_native_"));user=profile.get("user").asText();password=profile.get("password").asText();
        try(var c=owner()){
            assertEquals("0",scalar(c,"SELECT count(*) FROM pg_tables WHERE schemaname='public'"));sql(c,"REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            try(var paths=Files.list(Path.of(System.getProperty("kyc.manual.test.migrations")))){for(Path file:paths.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).sorted(Comparator.comparingInt(p->Integer.parseInt(p.getFileName().toString().split("__")[0].substring(1)))).toList())sql(c,Files.readString(file));}
            sql(c,"CREATE ROLE firefly_audit_guard NOLOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS");sql(c,"CREATE ROLE firefly_audit_writer LOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS PASSWORD '"+writerPassword+"'");sql(c,"CREATE ROLE firefly_audit_reader LOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS PASSWORD '"+UUID.randomUUID()+"'");
            for(String resource:List.of("db/optional/immutable-audit/postgresql.sql","db/optional/immutable-audit/completed-append.sql")){try(var stream=KycManualDecisionNativeIT.class.getClassLoader().getResourceAsStream(resource)){assertNotNull(stream);sql(c,new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
            sql(c,"SET ROLE firefly_audit_guard;GRANT EXECUTE ON FUNCTION public.firefly_audit_require_completed(uuid,uuid,bigint,text,boolean) TO firefly_audit_writer;RESET ROLE");
            sql(c,Files.readString(Path.of(System.getProperty("kyc.manual.test.migration"))));sql(c,"CREATE ROLE kyc_manual_test_reader LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+readerPassword+"' IN ROLE kyc_manual_reader");
        }
    }
    static java.sql.Connection owner()throws Exception{return DriverManager.getConnection("jdbc:postgresql://"+host+":"+port+"/"+database,user,password);}
    static void sql(java.sql.Connection c,String text)throws Exception{try(var statement=c.createStatement()){statement.execute(text);}}
    static String scalar(java.sql.Connection c,String text)throws Exception{try(var statement=c.createStatement();var rows=statement.executeQuery(text)){assertTrue(rows.next());return rows.getString(1);}}
    static Map<String,String> snapshot()throws Exception{var result=new TreeMap<String,String>();try(var c=owner()){
        var tables=new TreeSet<String>();try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN('public','firefly_audit') ORDER BY 1")){while(rows.next())tables.add(rows.getString(1));}
        for(String table:tables){assertTrue(table.matches("[a-z_]+\\.[a-z_]+"));result.put(table,scalar(c,"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t"));}
    }return result;}
    static KycDecisionSession session(boolean reader){
        var raw=ConnectionFactories.get(ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER,"postgresql").option(ConnectionFactoryOptions.HOST,host).option(ConnectionFactoryOptions.PORT,port).option(ConnectionFactoryOptions.DATABASE,database).option(ConnectionFactoryOptions.USER,reader?"kyc_manual_test_reader":"firefly_audit_writer").option(ConnectionFactoryOptions.PASSWORD,reader?readerPassword:writerPassword).build());
        var factory=new OwnedLeaseConnectionFactoryImpl(raw);var client=DatabaseClient.create(factory);var template=new R2dbcEntityTemplate(client,org.springframework.data.r2dbc.dialect.PostgresDialect.INSTANCE);
        return new KycDecisionSession(reader?KycDecisionSession.Role.READER:KycDecisionSession.Role.COMMAND,factory,client,template,new R2dbcTransactionManager(factory));
    }
    static final class Fixture {
        final Instant tokenIssued=Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Scope scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());final UUID maker=UUID.randomUUID(),reviewer=UUID.randomUUID(),document=UUID.randomUUID();
        final AtomicReference<UUID> staff=new AtomicReference<>(maker);final AtomicBoolean permitted=new AtomicBoolean(true),partyAvailable=new AtomicBoolean(true);
        final AtomicLong responseClockAdvance=new AtomicLong();final AtomicBoolean advanceAfterCommit=new AtomicBoolean();final AtomicInteger partyCalls=new AtomicInteger();
        final KycDecisionSession command=session(false),reader=session(true);KycDecisionService service;
        final CurrentKycDecisionAuthorityImpl authority;final KycPartyReader party;
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.now().plusMillis(responseClockAdvance.get());}};
        SubmitRequest request=new SubmitRequest(UUID.randomUUID(),-1,List.of(new Evidence(document,"controlled-document-owner","fixture-document","1".repeat(64),Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS))),Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS));
        Fixture()throws Exception{this(ZoneId.of("UTC"));}
        Fixture(ZoneId zone)throws Exception{
            var clients=new EnumMap<Operation,Set<String>>(Operation.class);for(var operation:Operation.values())clients.put(operation,Set.of("kyc-native"));
            authority=new CurrentKycDecisionAuthorityImpl(()->Mono.just(principal()),(p,a,r,c)->Mono.just(new PolicyEvaluation(permitted.get()?Decision.permit():Decision.deny("withdrawn"),Optional.of(new PolicyEvaluationProvenance("controlled-policy-1","controlled-decision")))),new Profile("https://kyc.native.invalid","core-kyc",clients),Clock.systemUTC(),System::nanoTime);
            party=permit->Mono.defer(()->{if(partyCalls.incrementAndGet()==2&&advanceAfterCommit.get())responseClockAdvance.set(4300);return partyAvailable.get()?Mono.just(new KycPartyReader.Observation(scope.tenantId(),scope.partyId(),"INDIVIDUAL",Instant.now(),permit.until())):Mono.error(new IllegalStateException("CONTROLLED_CUSTOMER_UNAVAILABLE"));});
            useSourceZone(zone);
            try(var c=owner()){
                sql(c,"INSERT INTO kyc_verification(kyc_verification_id,party_id,verification_status,date_created) VALUES('"+scope.kycCaseId()+"','"+scope.partyId()+"','VERIFIED',CURRENT_TIMESTAMP)");
                sql(c,"INSERT INTO verification_document(verification_document_id,kyc_verification_id,document_type,document_reference,document_system_id,is_verified,date_created) VALUES('"+document+"','"+scope.kycCaseId()+"','PASSPORT','fixture-document','controlled-document-owner',true,CURRENT_TIMESTAMP)");
            }
        }
        void useSourceZone(ZoneId zone){service=new KycDecisionServiceImpl(authority,party,command,reader,Duration.ofMinutes(10),zone,clock,System::nanoTime,UUID::randomUUID);}
        SecurityPrincipal principal(){UUID actor=staff.get();var claims=Map.<String,Object>of("tenant_id",scope.tenantId().toString(),"client_id","kyc-native","principal_kind","STAFF","staff_actor_id",actor.toString(),"staff_realm","controlled-staff","aud",List.of("core-kyc"),"exp",tokenIssued.plusSeconds(120),"nbf",tokenIssued.minusSeconds(60));return new SecurityPrincipal(actor.toString(),"https://kyc.native.invalid",scope.tenantId().toString(),Set.of(),Set.of("kyc:manual-decisions:submit","kyc:manual-decisions:review","kyc:manual-decisions:revoke","kyc:manual-decisions:current","kyc:manual-decisions:recover"),claims,Instant.EPOCH,null,Set.of(),Map.of());}
        ActionReceipt submit(){return service.submit(scope,request).block(LIMIT);}
        ActionReceipt approve(ActionReceipt submitted){staff.set(reviewer);return service.review(scope,new ReviewRequest(UUID.randomUUID(),submitted.actionId(),submitted.submissionDigest(),submitted.resultingControlVersion(),ReviewOutcome.APPROVE,"MANUAL_EVIDENCE_REVIEWED")).block(LIMIT);}
        CurrentDecision current(ActionReceipt reviewed){return service.current(scope,reviewed.actionId()).block(LIMIT);}
    }
    @Test @Order(1) void submittedDecisionPairsOriginalStaffAuditAndEvidenceAtomically()throws Exception{var f=new Fixture();var result=f.submit();assertEquals(State.SUBMITTED,result.resultingState());assertEquals(0,result.resultingControlVersion());assertNotNull(result.submissionDigest());try(var c=owner()){
        assertEquals("1",scalar(c,"SELECT count(*) FROM kyc_manual_audit b JOIN firefly_audit.audit_event e ON e.resource_tenant_id=b.tenant_id AND e.event_id=b.event_id AND e.sequence=b.audit_sequence AND e.record_hash=b.audit_record_hash WHERE b.action_id='"+result.actionId()+"'"));
        assertEquals("1",scalar(c,"SELECT count(*) FROM firefly_audit_private.context x JOIN kyc_manual_audit b ON b.audit_context_nonce=x.nonce WHERE b.action_id='"+result.actionId()+"' AND x.db_session_user='firefly_audit_writer' AND x.new_event"));
        assertEquals("1",scalar(c,"SELECT count(DISTINCT x) FROM (SELECT xmin::text x FROM kyc_manual_control WHERE kyc_case_id='"+f.scope.kycCaseId()+"' UNION ALL SELECT xmin::text FROM kyc_manual_action WHERE action_id='"+result.actionId()+"' UNION ALL SELECT xmin::text FROM kyc_manual_evidence WHERE submission_action_id='"+result.actionId()+"' UNION ALL SELECT xmin::text FROM kyc_manual_audit WHERE action_id='"+result.actionId()+"') rows"));assertEquals("0",scalar(c,"SELECT count(*) FROM kyc_manual_private.context"));}}
    @Test @Order(2) void exactSubmissionReplayKeepsAllBusinessAndCanonicalAuditRows()throws Exception{var f=new Fixture();var original=f.submit();var before=snapshot();assertEquals(original,f.submit());assertEquals(before,snapshot());}
    @Test @Order(3) void independentReviewMakesOnlyExplicitManualCurrentDecision()throws Exception{var f=new Fixture();var submitted=f.submit();var reviewed=f.approve(submitted);assertEquals(State.APPROVED,reviewed.resultingState());var before=snapshot();var current=f.current(reviewed);assertEquals(DecisionMode.MANUAL_REVIEW,current.mode());assertEquals(ExternalExecution.NOT_REQUESTED,current.externalExecution());assertEquals(reviewed.actionId(),current.decisionActionId());assertEquals(submitted.actionId(),current.submissionActionId());assertEquals(before,snapshot());}
    @Test @Order(4) void sameMakerCannotApproveOwnSubmission()throws Exception{var f=new Fixture();var submitted=f.submit();var before=snapshot();assertThrows(KycDecisionException.class,()->f.service.review(f.scope,new ReviewRequest(UUID.randomUUID(),submitted.actionId(),submitted.submissionDigest(),0,ReviewOutcome.APPROVE,"MANUAL_REVIEW")).block(LIMIT));assertEquals(before,snapshot());}
    @Test @Order(5) void revocationWithholdsCurrentButPreservesOriginalActionRecovery()throws Exception{var f=new Fixture();var reviewed=f.approve(f.submit());var revoked=f.service.revoke(f.scope,new RevokeRequest(UUID.randomUUID(),reviewed.actionId(),1,"EVIDENCE_WITHDRAWN")).block(LIMIT);assertEquals(State.REVOKED,revoked.resultingState());var before=snapshot();assertThrows(KycDecisionException.class,()->f.current(reviewed));assertEquals(reviewed,f.service.recover(f.scope,reviewed.actionId()).block(LIMIT));assertEquals(before,snapshot());}
    @Test @Order(6) void changedSubmissionIntentCannotReuseOriginalAction()throws Exception{var f=new Fixture();f.submit();var before=snapshot();f.request=new SubmitRequest(f.request.actionId(),-1,f.request.evidence(),f.request.validUntil().plusSeconds(1));assertEquals(KycDecisionException.Reason.CONFLICT,assertThrows(KycDecisionException.class,f::submit).reason());assertEquals(before,snapshot());}
    @Test @Order(7) void legacyVerifiedFlagsAloneNeverProduceCurrentDecision()throws Exception{var f=new Fixture();var before=snapshot();assertThrows(KycDecisionException.class,()->f.service.current(f.scope,UUID.randomUUID()).block(LIMIT));assertEquals(before,snapshot());}
    @Test @Order(8) void missingCurrentCustomerRefusesBeforeBusinessMutation()throws Exception{var f=new Fixture();f.partyAvailable.set(false);var before=snapshot();assertThrows(KycDecisionException.class,f::submit);assertEquals(before,snapshot());}
    @Test @Order(9) void policyWithdrawalDuringMappedEvidenceWriteRollsBackEveryEffect()throws Exception{var f=new Fixture();var before=snapshot();org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback<KycDecisionRows.Evidence> callback=(row,table)->{f.permitted.set(false);return Mono.just(row);};f.command.operations().setEntityCallbacks(org.springframework.data.mapping.callback.ReactiveEntityCallbacks.create(callback));var error=assertThrows(KycDecisionException.class,f::submit);assertTrue(error.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());}
    @Test @Order(10) void readerCannotWriteBusinessRowsAndRecoveryDoesNotMutate()throws Exception{var f=new Fixture();var original=f.submit();var before=snapshot();assertEquals(original,f.service.recover(f.scope,original.actionId()).block(LIMIT));assertThrows(RuntimeException.class,()->f.reader.operations().insert(new KycDecisionRows.Control(f.scope.kycCaseId(),f.scope.tenantId(),f.scope.partyId(),original.actionId(),null,"SUBMITTED",0)).block(LIMIT));assertEquals(before,snapshot());}
    @Test @Order(11) void auditMappingSubstitutionCannotCommitAReceipt()throws Exception{var f=new Fixture();var before=snapshot();org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback<KycDecisionRows.Audit> callback=(row,table)->Mono.just(new KycDecisionRows.Audit(row.actionId(),row.tenantId(),row.kycCaseId(),row.eventId(),row.auditContextNonce(),row.auditSequence(),"0".repeat(64)));f.command.operations().setEntityCallbacks(org.springframework.data.mapping.callback.ReactiveEntityCallbacks.create(callback));var error=assertThrows(KycDecisionException.class,f::submit);assertTrue(error.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());}
    @Test @Order(12) void canonicalAccessorRevocationPreventsNewAndReplayAcceptance()throws Exception{var f=new Fixture();f.submit();var fresh=new Fixture();var before=snapshot();try(var c=owner()){sql(c,"SET ROLE firefly_audit_guard;REVOKE EXECUTE ON FUNCTION public.firefly_audit_require_completed(uuid,uuid,bigint,text,boolean) FROM firefly_audit_writer;RESET ROLE");}try{for(var selected:List.of(f,fresh))assertTrue(assertThrows(KycDecisionException.class,selected::submit).transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());}finally{try(var c=owner()){sql(c,"SET ROLE firefly_audit_guard;GRANT EXECUTE ON FUNCTION public.firefly_audit_require_completed(uuid,uuid,bigint,text,boolean) TO firefly_audit_writer;RESET ROLE");}}}
    @Test @Order(13) void boundExistingCaseAndDocumentCannotBeReassignedOrRewritten()throws Exception{var f=new Fixture();f.submit();var before=snapshot();try(var c=owner()){assertEquals("23514",assertThrows(java.sql.SQLException.class,()->sql(c,"UPDATE kyc_verification SET party_id='"+UUID.randomUUID()+"' WHERE kyc_verification_id='"+f.scope.kycCaseId()+"'")).getSQLState());assertEquals("23514",assertThrows(java.sql.SQLException.class,()->sql(c,"UPDATE verification_document SET document_reference='changed' WHERE verification_document_id='"+f.document+"'")).getSQLState());assertEquals("23514",assertThrows(java.sql.SQLException.class,()->sql(c,"DELETE FROM verification_document WHERE verification_document_id='"+f.document+"'")).getSQLState());}assertEquals(before,snapshot());}
    @Test @Order(14) void concurrentIndependentReviewHasOnlyOneDurableWinner()throws Exception{var f=new Fixture();var submitted=f.submit();f.staff.set(f.reviewer);var approve=new ReviewRequest(UUID.randomUUID(),submitted.actionId(),submitted.submissionDigest(),0,ReviewOutcome.APPROVE,"MANUAL_APPROVE");var reject=new ReviewRequest(UUID.randomUUID(),submitted.actionId(),submitted.submissionDigest(),0,ReviewOutcome.REJECT,"MANUAL_REJECT");var outcomes=Mono.zip(f.service.review(f.scope,approve).materialize().subscribeOn(Schedulers.parallel()),f.service.review(f.scope,reject).materialize().subscribeOn(Schedulers.parallel())).block(LIMIT);assertEquals(1,(outcomes.getT1().isOnNext()?1:0)+(outcomes.getT2().isOnNext()?1:0));try(var c=owner()){assertEquals("2",scalar(c,"SELECT count(*) FROM kyc_manual_action WHERE kyc_case_id='"+f.scope.kycCaseId()+"'"));assertEquals("2",scalar(c,"SELECT count(*) FROM kyc_manual_audit WHERE kyc_case_id='"+f.scope.kycCaseId()+"'"));}}
    @Test @Order(15) void wrongTenantPartyAndOriginalRecoveryActorCannotDiscloseReceipt()throws Exception{var f=new Fixture();var original=f.submit();var before=snapshot();assertThrows(KycDecisionException.class,()->f.service.recover(new Scope(UUID.randomUUID(),f.scope.partyId(),f.scope.kycCaseId()),original.actionId()).block(LIMIT));assertThrows(KycDecisionException.class,()->f.service.recover(new Scope(f.scope.tenantId(),UUID.randomUUID(),f.scope.kycCaseId()),original.actionId()).block(LIMIT));f.staff.set(f.reviewer);assertThrows(KycDecisionException.class,()->f.service.recover(f.scope,original.actionId()).block(LIMIT));assertEquals(before,snapshot());}
    @Test @Order(16) void committedResponseCannotOutliveNarrowerDatabaseDeadline()throws Exception{
        var f=new Fixture();f.advanceAfterCommit.set(true);
        var withheld=assertThrows(KycDecisionException.class,f::submit);
        assertEquals(KycDecisionException.Reason.WITHHELD,withheld.reason());assertTrue(withheld.transactionFacts().commitAcknowledged());
        f.responseClockAdvance.set(0);f.advanceAfterCommit.set(false);var before=snapshot();
        var recovered=f.service.recover(f.scope,f.request.actionId()).block(LIMIT);
        assertEquals(f.request.actionId(),recovered.actionId());assertEquals(State.SUBMITTED,recovered.resultingState());assertEquals(before,snapshot());
    }

    @Test @Order(17) void expiredOriginalDocumentCannotBecomeCurrentManualEvidence()throws Exception{
        var f=new Fixture();try(var c=owner()){sql(c,"UPDATE verification_document SET expiry_date=TIMESTAMP '2000-01-01 00:00:00' WHERE verification_document_id='"+f.document+"'");}
        var before=snapshot();var failure=assertThrows(KycDecisionException.class,f::submit);assertEquals(KycDecisionException.Reason.REFUSED,failure.reason());assertTrue(failure.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());
    }
    @Test @Order(18) void evidenceWindowCannotExceedResolvedOriginalDocumentExpiry()throws Exception{
        var f=new Fixture();var expiry=LocalDateTime.ofInstant(Instant.now().plusSeconds(120),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        try(var c=owner()){sql(c,"UPDATE verification_document SET expiry_date=TIMESTAMP '"+expiry+"' WHERE verification_document_id='"+f.document+"'");}
        var before=snapshot();var failure=assertThrows(KycDecisionException.class,f::submit);assertEquals(KycDecisionException.Reason.REFUSED,failure.reason());assertTrue(failure.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());
    }

    @Test @Order(19) void unboundLegacyCaseAndDocumentCanStillBeUpdatedAndDeleted()throws Exception{
        var f=new Fixture();var changedParty=UUID.randomUUID();try(var c=owner()){
            sql(c,"UPDATE verification_document SET expiry_date=TIMESTAMP '2099-01-01 00:00:00' WHERE verification_document_id='"+f.document+"'");
            assertEquals("2099-01-01 00:00:00",scalar(c,"SELECT expiry_date::text FROM verification_document WHERE verification_document_id='"+f.document+"'"));
            sql(c,"UPDATE kyc_verification SET party_id='"+changedParty+"' WHERE kyc_verification_id='"+f.scope.kycCaseId()+"'");
            assertEquals(changedParty.toString(),scalar(c,"SELECT party_id::text FROM kyc_verification WHERE kyc_verification_id='"+f.scope.kycCaseId()+"'"));
            sql(c,"DELETE FROM verification_document WHERE verification_document_id='"+f.document+"'");sql(c,"DELETE FROM kyc_verification WHERE kyc_verification_id='"+f.scope.kycCaseId()+"'");
        }
    }


    static Instant datedSource(Fixture f,ZoneId zone)throws Exception{
        Instant expiry=Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS);
        try(var c=owner()){sql(c,"UPDATE verification_document SET expiry_date=TIMESTAMP '"+LocalDateTime.ofInstant(expiry,zone)+"' WHERE verification_document_id='"+f.document+"'");}return expiry;
    }
    @Test @Order(20) void futureDatedSourceBindsExactInterpretationIntoCanonicalAudit()throws Exception{
        var zone=ZoneId.of("America/New_York");var f=new Fixture(zone);Instant expiry=datedSource(f,zone);var submitted=f.submit();
        try(var c=owner()){
            String payload=scalar(c,"SELECT payload FROM kyc_manual_action WHERE action_id='"+submitted.actionId()+"'");
            var source=KycDecisionCodec.decode(payload,Submission.class);KycManualDecisionRules.requireSubmission(source);
            var binding=source.sourceExpiries().getFirst();assertEquals(f.document,binding.documentId());assertEquals(LocalDateTime.ofInstant(expiry,zone),binding.sourceDate());assertEquals(zone.getId(),binding.zoneId());assertEquals(zone.getRules().getOffset(expiry).getTotalSeconds(),binding.offsetSeconds());assertEquals(expiry,binding.expiresAt());assertEquals(submitted.submissionDigest(),source.digest());
            assertEquals(KycDecisionCodec.payloadDigest(payload),scalar(c,"SELECT payload_digest FROM kyc_manual_action WHERE action_id='"+submitted.actionId()+"'"));
            assertEquals("1",scalar(c,"SELECT count(*) FROM kyc_manual_action a JOIN kyc_manual_evidence e ON e.submission_action_id=a.action_id JOIN kyc_manual_audit b ON b.action_id=a.action_id JOIN firefly_audit.audit_event canonical ON canonical.resource_tenant_id=b.tenant_id AND canonical.event_id=b.event_id AND canonical.sequence=b.audit_sequence AND canonical.record_hash=b.audit_record_hash WHERE a.action_id='"+submitted.actionId()+"' AND canonical.evidence_digest=kyc_manual_private.action_evidence(a) AND e.source_expiry_zone='America/New_York' AND e.source_expires_at=TIMESTAMPTZ '"+expiry+"' AND (a.payload::jsonb->'sourceExpiries'->0->>'expiresAt')::timestamptz=e.source_expires_at"));
        }
        var reviewed=f.approve(submitted);var before=snapshot();assertEquals(submitted.submissionDigest(),f.current(reviewed).submissionDigest());assertEquals(before,snapshot());
    }
    @Test @Order(21) void changedDeploymentZoneCannotReinterpretOriginalEvidenceOrReceipt()throws Exception{
        var originalZone=ZoneId.of("America/New_York");var f=new Fixture(originalZone);datedSource(f,originalZone);var submitted=f.submit();
        f.useSourceZone(ZoneId.of("Pacific/Honolulu"));assertEquals(submitted,f.submit());var reviewed=f.approve(submitted);var before=snapshot();
        assertEquals(submitted.submissionDigest(),f.current(reviewed).submissionDigest());assertEquals(before,snapshot());
        f.useSourceZone(ZoneId.of("UTC"));assertEquals(reviewed,f.service.recover(f.scope,reviewed.actionId()).block(LIMIT));assertEquals(submitted.submissionDigest(),f.current(reviewed).submissionDigest());assertEquals(before,snapshot());
    }
    @Test @Order(22) void ambiguousAndMissingCivilOffsetsCannotProducePersistedEvidence()throws Exception{
        for(String date:List.of("2027-03-14 02:30:00","2027-11-07 01:30:00")){
            var f=new Fixture(ZoneId.of("America/New_York"));try(var c=owner()){sql(c,"UPDATE verification_document SET expiry_date=TIMESTAMP '"+date+"' WHERE verification_document_id='"+f.document+"'");}
            var before=snapshot();var failure=assertThrows(KycDecisionException.class,f::submit);assertEquals(KycDecisionException.Reason.REFUSED,failure.reason());assertTrue(failure.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());
        }
    }
    @Test @Order(23) void mappedExpirySubstitutionCannotChangeDigestBoundInterpretation()throws Exception{
        for(boolean changeZone:List.of(false,true)){
            var f=new Fixture();datedSource(f,ZoneId.of("UTC"));var before=snapshot();
            org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback<KycDecisionRows.Evidence> callback=(row,table)->Mono.just(new KycDecisionRows.Evidence(row.bindingId(),row.tenantId(),row.partyId(),row.kycCaseId(),row.submissionActionId(),row.documentId(),row.identityDocumentId(),row.documentType(),row.verificationPurpose(),row.documentReference(),row.documentSystemId(),row.sourceExpiryDate(),changeZone?"Africa/Abidjan":row.sourceExpiryZone(),row.sourceExpiryOffsetSeconds(),changeZone?row.sourceExpiresAt():row.sourceExpiresAt().plusSeconds(1),row.contentDigest(),row.collectedAt(),row.validUntil()));
            f.command.operations().setEntityCallbacks(org.springframework.data.mapping.callback.ReactiveEntityCallbacks.create(callback));var failure=assertThrows(KycDecisionException.class,f::submit);assertTrue(failure.transactionFacts().rollbackAcknowledged());assertEquals(before,snapshot());
        }
    }

}
