package io.fireflysoftware.core.kycb.core.reproofing;

import io.fireflysoftware.core.kycb.interfaces.reproofing.*;
import io.fireflysoftware.core.kycb.core.reproofing.impl.ReproofingServiceImpl;
import io.r2dbc.spi.ConnectionFactories;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReproofingPostgresTest {
    DatabaseClient db;
    ReproofingServiceImpl service;
    Fixture nativeOwner;
    ReproofingBinding binding;
    @BeforeEach void setup() {
        String url=System.getenv("REPROOFING_TEST_R2DBC_URL");
        assertNotNull(url,"Hosted owned PostgreSQL fixture is required; tests must not skip");
        var factory=ConnectionFactories.get(io.r2dbc.spi.ConnectionFactoryOptions.builder().from(io.r2dbc.spi.ConnectionFactoryOptions.parse(url)).option(io.r2dbc.spi.ConnectionFactoryOptions.USER,System.getenv("REPROOFING_TEST_USER")).option(io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD,System.getenv("REPROOFING_TEST_PASSWORD")).build());
        db=DatabaseClient.create(factory);
        nativeOwner=new Fixture();
        binding=new ReproofingBinding("https://id.example.test/realms/customer","customer","bank","subject-"+UUID.randomUUID(),UUID.randomUUID(),"HUMAN","CREDENTIAL_REPLACEMENT","epoch","revision",UUID.randomUUID().toString());
        service=new ReproofingServiceImpl(db,TransactionalOperator.create(new R2dbcTransactionManager(factory)),nativeOwner,nativeOwner,"provider","policy","https://id.example.test/realms/customer","customer","bank");
    }
    ReproofingCaseResponse start() {
        var c=service.create(new CreateReproofingCaseRequest(binding)).block();
        return service.bindSession(c.caseId(),new BindReproofingSessionRequest(binding,"session-"+c.providerStartId())).block();
    }
    ReproofingCaseResponse confirm(ReproofingCaseResponse c) {return service.confirm(c.caseId(),new ConfirmReproofingRequest(binding)).block();}
    ReproofingAuthorizationRequest request(ReproofingCaseResponse c) {return new ReproofingAuthorizationRequest(binding,c.authorization().authorizationId(),c.authorization().executionId());}
    @Test void createRetriesPreserveReservationExpiryAndImmutableSession() {
        var c=start();var retry=service.create(new CreateReproofingCaseRequest(binding)).block();
        assertEquals(c.caseId(),retry.caseId());assertEquals(c.providerStartId(),retry.providerStartId());assertEquals(c.expiresAt(),retry.expiresAt());
        assertThrows(RuntimeException.class,()->service.bindSession(c.caseId(),new BindReproofingSessionRequest(binding,"substitute")).block());
    }
    @Test void forgedReferenceCannotGrantOrExhaustVictimBudget() {
        nativeOwner.wrongReference=true;
        for(int i=0;i<4;i++){var c=start();assertNull(confirm(c).authorization());binding=withNonce();}
        nativeOwner.wrongReference=false;assertNotNull(confirm(start()).authorization());
    }
    @Test void outagesDoNotChargeCaseOrSubjectBudget() {
        var c=start();nativeOwner.unavailable=true;
        for(int i=0;i<4;i++)assertThrows(RuntimeException.class,()->confirm(c));
        nativeOwner.unavailable=false;assertNotNull(confirm(c).authorization());
    }
    @Test void positivelyMatchedSubjectFailureBudgetSurvivesNewCases() {
        nativeOwner.documentAuthentic=false;
        for(int i=0;i<3;i++){assertNull(confirm(start()).authorization());binding=withNonce();}
        nativeOwner.documentAuthentic=true;assertNull(confirm(start()).authorization());
    }
    @Test void decoyPrepareDoesNotAuthorizeReplacement() {
        var c=start();nativeOwner.eligible=false;assertThrows(RuntimeException.class,()->confirm(c));
        assertNull(service.inspect(c.caseId(),new ConfirmReproofingRequest(binding)).block().authorization());
    }
    @Test void concurrentConsumersProduceOneTransitionAndCanonicalReceipt() {
        var c=confirm(start());var req=request(c);
        var receipts=Flux.range(0,12).flatMap(i->service.consume(c.caseId(),req),12).collectList().block();
        assertEquals(12,receipts.size());assertTrue(receipts.stream().allMatch(a->a.equals(c.authorization())));
        Long count=db.sql("SELECT count(*) n FROM identity_reproofing_audit WHERE case_id=:id AND event='CONSUMED'").bind("id",c.caseId()).map((row,m)->row.get("n",Long.class)).one().block();
        assertEquals(1L,count);
        assertThrows(RuntimeException.class,()->service.consume(c.caseId(),new ReproofingAuthorizationRequest(binding,req.authorizationId(),UUID.randomUUID())).block());
    }
    @Test void verifyRefusesUnconsumedGrantAndNativeHoldRefusesConsumption() {
        var c=confirm(start());assertThrows(RuntimeException.class,()->service.verify(c.caseId(),request(c)).block());
        nativeOwner.eligible=false;assertThrows(RuntimeException.class,()->service.consume(c.caseId(),request(c)).block());
    }
    @Test void expiredConsumedAuthorizationCannotVerifyButExactCommittedJournalReconciles() {
        var c=confirm(start());var req=request(c);service.consume(c.caseId(),req).block();
        db.sql("UPDATE identity_reproofing_case SET grant_expires_at=clock_timestamp()-interval '1 second' WHERE case_id=:id").bind("id",c.caseId()).fetch().rowsUpdated().block();
        assertThrows(RuntimeException.class,()->service.verify(c.caseId(),req).block());
        nativeOwner.journal=new ReproofingExecution(c.caseId(),req.executionId(),binding,"CREDENTIALS_REPLACED",Instant.now().minusSeconds(2));
        var record=new RecordReproofingExecutionRequest(binding,req.authorizationId(),req.executionId(),"CREDENTIALS_REPLACED");
        assertEquals("EXECUTED",service.record(c.caseId(),record).block().status());
        assertEquals("EXECUTED",service.record(c.caseId(),record).block().status());
    }
    @Test void substitutedEvidenceSessionAndExpiredEvidenceAreRefused() {
        var c=start();nativeOwner.wrongSession=true;assertNull(confirm(c).authorization());
        binding=withNonce();nativeOwner.wrongSession=false;nativeOwner.expired=true;assertNull(confirm(start()).authorization());
    }
    @Test void finalizedGrantRetryPreservesGrantAndNeverRechecksEvidence() {
        var c=confirm(start());nativeOwner.unavailable=true;
        assertEquals(c.authorization(),confirm(c).authorization());assertEquals(1,nativeOwner.verifications.get());
    }
    ReproofingBinding withNonce(){return new ReproofingBinding(binding.issuer(),binding.realm(),binding.tenant(),binding.subject(),binding.partyId(),binding.population(),binding.purpose(),binding.generation(),binding.credentialRevision(),UUID.randomUUID().toString());}
    static class Fixture implements ReproofingNativePort,ReproofingEvidencePort {
        boolean eligible=true,unavailable,wrongReference,wrongSession,expired,documentAuthentic=true;
        AtomicInteger verifications=new AtomicInteger();ReproofingExecution journal;
        public Mono<ReproofingAdmission> prepare(ReproofingNativeRequest r){return Mono.just(new ReproofingAdmission(r.binding(),true,Instant.now().plusSeconds(600)));}
        public Mono<ReproofingAdmission> admit(ReproofingNativeRequest r){return Mono.just(new ReproofingAdmission(r.binding(),eligible,Instant.now().plusSeconds(100)));}
        public Mono<ReproofingExecution> journal(ReproofingNativeRequest r){return Mono.justOrEmpty(journal);}
        public Mono<VerifiedReproofingEvidence> verify(ReproofingEvidenceRequest r){
            verifications.incrementAndGet();if(unavailable)return Mono.error(new IllegalStateException("Fixture outage"));
            return Mono.just(new VerifiedReproofingEvidence(r.caseId(),r.binding(),r.providerId(),r.policyId(),wrongSession?"other":r.providerSessionId(),"evidence-"+r.caseId(),"sha256:"+"a".repeat(64),wrongReference?UUID.randomUUID():r.binding().partyId(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(expired?-1:90),documentAuthentic,true,true,!wrongReference));
        }
    }
}
