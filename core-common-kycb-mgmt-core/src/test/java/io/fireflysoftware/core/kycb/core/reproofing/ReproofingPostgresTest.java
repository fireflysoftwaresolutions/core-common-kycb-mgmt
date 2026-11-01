package io.fireflysoftware.core.kycb.core.reproofing;

import static org.junit.jupiter.api.Assertions.*;

import io.fireflysoftware.core.kycb.core.reproofing.impl.ReproofingServiceImpl;
import io.fireflysoftware.core.kycb.interfaces.reproofing.*;
import io.r2dbc.spi.ConnectionFactories;

import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class ReproofingPostgresTest {
    DatabaseClient db;
    ReproofingServiceImpl service;
    Fixture nativeOwner;
    ReproofingBinding binding;

    @BeforeEach
    void setup() {
        String url = System.getenv("REPROOFING_TEST_R2DBC_URL");
        assertNotNull(url, "Hosted owned PostgreSQL fixture is required; tests must not skip");
        var factory =
                ConnectionFactories.get(
                        io.r2dbc.spi.ConnectionFactoryOptions.builder()
                                .from(io.r2dbc.spi.ConnectionFactoryOptions.parse(url))
                                .option(
                                        io.r2dbc.spi.ConnectionFactoryOptions.USER,
                                        System.getenv("REPROOFING_TEST_USER"))
                                .option(
                                        io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD,
                                        System.getenv("REPROOFING_TEST_PASSWORD"))
                                .build());
        db = DatabaseClient.create(factory);
        nativeOwner = new Fixture();
        binding =
                new ReproofingBinding(
                        "https://id.example.test/realms/customer",
                        "customer",
                        "bank",
                        "subject-" + UUID.randomUUID(),
                        UUID.randomUUID(),
                        "HUMAN",
                        "CREDENTIAL_REPLACEMENT",
                        "epoch",
                        "revision",
                        UUID.randomUUID().toString());
        service =
                new ReproofingServiceImpl(
                        db,
                        TransactionalOperator.create(new R2dbcTransactionManager(factory)),
                        nativeOwner,
                        nativeOwner,
                        "provider",
                        "policy",
                        "https://id.example.test/realms/customer",
                        "customer",
                        "bank");
    }

    ReproofingCaseResponse start() {
        var c = service.create(new CreateReproofingCaseRequest(binding)).block();
        return service.bindSession(
                        c.caseId(),
                        new BindReproofingSessionRequest(binding, "session-" + c.providerStartId()))
                .block();
    }

    ReproofingCaseResponse confirm(ReproofingCaseResponse c) {
        return service.confirm(c.caseId(), new ConfirmReproofingRequest(binding)).block();
    }

    ReproofingAuthorizationRequest request(ReproofingCaseResponse c) {
        return new ReproofingAuthorizationRequest(
                binding, c.authorization().authorizationId(), c.authorization().executionId());
    }

    @Test
    void createRetriesPreserveReservationExpiryAndImmutableSession() {
        var c = start();
        var retry = service.create(new CreateReproofingCaseRequest(binding)).block();
        assertEquals(c.caseId(), retry.caseId());
        assertEquals(c.providerStartId(), retry.providerStartId());
        assertEquals(c.expiresAt(), retry.expiresAt());
        assertThrows(
                RuntimeException.class,
                () ->
                        service.bindSession(
                                        c.caseId(),
                                        new BindReproofingSessionRequest(binding, "substitute"))
                                .block());
    }

    @Test
    void forgedReferenceCannotGrantOrExhaustVictimBudget() {
        nativeOwner.wrongReference = true;
        for (int i = 0; i < 4; i++) {
            var c = start();
            assertNull(confirm(c).authorization());
            binding = withNonce();
        }
        nativeOwner.wrongReference = false;
        assertNotNull(confirm(start()).authorization());
    }

    @Test
    void outagesDoNotChargeCaseOrSubjectBudget() {
        var c = start();
        nativeOwner.unavailable = true;
        for (int i = 0; i < 4; i++) assertThrows(RuntimeException.class, () -> confirm(c));
        nativeOwner.unavailable = false;
        assertNotNull(confirm(c).authorization());
    }

    @Test
    void fullyVerifiedGrantBudgetSurvivesNewCasesAndRestart() {
        for (int i = 0; i < 3; i++) {
            assertNotNull(confirm(start()).authorization());
            binding = withNonce();
        }
        var freshFactory =
                ConnectionFactories.get(
                        io.r2dbc.spi.ConnectionFactoryOptions.builder()
                                .from(
                                        io.r2dbc.spi.ConnectionFactoryOptions.parse(
                                                System.getenv("REPROOFING_TEST_R2DBC_URL")))
                                .option(
                                        io.r2dbc.spi.ConnectionFactoryOptions.USER,
                                        System.getenv("REPROOFING_TEST_USER"))
                                .option(
                                        io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD,
                                        System.getenv("REPROOFING_TEST_PASSWORD"))
                                .build());
        service =
                new ReproofingServiceImpl(
                        DatabaseClient.create(freshFactory),
                        TransactionalOperator.create(new R2dbcTransactionManager(freshFactory)),
                        nativeOwner,
                        nativeOwner,
                        "provider",
                        "policy",
                        "https://id.example.test/realms/customer",
                        "customer",
                        "bank");
        assertNull(confirm(start()).authorization());
    }

    @Test
    void failedMandatoryProofDoesNotChargeVictimSubject() {
        nativeOwner.documentAuthentic = false;
        for (int i = 0; i < 4; i++) {
            assertNull(confirm(start()).authorization());
            binding = withNonce();
        }
        nativeOwner.documentAuthentic = true;
        assertNotNull(confirm(start()).authorization());
    }

    @Test
    void authenticatedNegativeBoundsIneligibleCaseWithoutAdmissionOrVictimCharge() {
        var caseValue = start();
        nativeOwner.eligible = false;
        nativeOwner.documentAuthentic = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            var rejected = confirm(caseValue);
            assertNull(rejected.authorization());
            assertEquals(attempt == 2 ? "DENIED" : "PENDING", rejected.status());
        }
        assertThrows(RuntimeException.class, () -> confirm(caseValue));
        assertEquals(0, nativeOwner.admissions.get());
        Integer failures = db.sql("SELECT failures FROM identity_reproofing_case WHERE case_id=:id")
                .bind("id", caseValue.caseId()).map((row, metadata) -> row.get("failures", Integer.class)).one().block();
        Long budgets = db.sql("SELECT count(*) AS total FROM identity_reproofing_budget WHERE subject_budget_key=(SELECT subject_budget_key FROM identity_reproofing_case WHERE case_id=:id)")
                .bind("id", caseValue.caseId()).map((row, metadata) -> row.get("total", Long.class)).one().block();
        assertEquals(3, failures);
        assertEquals(0L, budgets);
    }

    @Test
    void expiredNegativeEvidenceCannotFinalizeARefusalOrInvokeAdmission() {
        var caseValue = start();
        nativeOwner.documentAuthentic = false;
        nativeOwner.expired = true;
        assertThrows(RuntimeException.class, () -> confirm(caseValue));
        assertEquals(0, nativeOwner.admissions.get());
        Integer failures = db.sql("SELECT failures FROM identity_reproofing_case WHERE case_id=:id")
                .bind("id", caseValue.caseId()).map((row, metadata) -> row.get("failures", Integer.class)).one().block();
        assertEquals(0, failures);
    }

    @Test
    void decoyPrepareDoesNotAuthorizeReplacement() {
        var c = start();
        nativeOwner.eligible = false;
        assertThrows(RuntimeException.class, () -> confirm(c));
        assertNull(
                service.inspect(c.caseId(), new ConfirmReproofingRequest(binding))
                        .block()
                        .authorization());
    }

    @Test
    void concurrentConsumersProduceOneTransitionAndCanonicalReceipt() {
        var c = confirm(start());
        var req = request(c);
        var receipts =
                Flux.range(0, 12)
                        .flatMap(i -> service.consume(c.caseId(), req), 12)
                        .collectList()
                        .block();
        assertEquals(12, receipts.size());
        assertTrue(receipts.stream().allMatch(a -> a.equals(c.authorization())));
        Long count =
                db.sql(
                                "SELECT count(*) n FROM identity_reproofing_audit WHERE case_id=:id"
                                    + " AND event='CONSUMED'")
                        .bind("id", c.caseId())
                        .map((row, m) -> row.get("n", Long.class))
                        .one()
                        .block();
        assertEquals(1L, count);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.consume(
                                        c.caseId(),
                                        new ReproofingAuthorizationRequest(
                                                binding, req.authorizationId(), UUID.randomUUID()))
                                .block());
    }

    @Test
    void verifyRefusesUnconsumedGrantAndNativeHoldRefusesConsumption() {
        var c = confirm(start());
        assertThrows(RuntimeException.class, () -> service.verify(c.caseId(), request(c)).block());
        nativeOwner.eligible = false;
        assertThrows(RuntimeException.class, () -> service.consume(c.caseId(), request(c)).block());
    }

    @Test
    void expiredConsumedAuthorizationCannotVerifyButExactCommittedJournalReconciles() {
        var c = confirm(start());
        var req = request(c);
        service.consume(c.caseId(), req).block();
        db.sql(
                        "UPDATE identity_reproofing_case SET"
                            + " grant_issued_at=clock_timestamp()-interval '10 seconds',grant_expires_at=clock_timestamp()-interval '1 second' WHERE"
                            + " case_id=:id")
                .bind("id", c.caseId())
                .fetch()
                .rowsUpdated()
                .block();
        assertThrows(RuntimeException.class, () -> service.verify(c.caseId(), req).block());
        nativeOwner.journal =
                new ReproofingExecution(
                        c.caseId(),
                        req.executionId(),
                        binding,
                        "CREDENTIALS_REPLACED",
                        Instant.now().minusSeconds(2));
        var record =
                new RecordReproofingExecutionRequest(
                        binding, req.authorizationId(), req.executionId(), "CREDENTIALS_REPLACED");
        assertEquals("EXECUTED", service.record(c.caseId(), record).block().status());
        assertEquals("EXECUTED", service.record(c.caseId(), record).block().status());
    }

    @Test
    void substitutedEvidenceSessionAndExpiredEvidenceAreRefused() {
        var c = start();
        nativeOwner.wrongSession = true;
        assertThrows(RuntimeException.class, () -> confirm(c));
        binding = withNonce();
        nativeOwner.wrongSession = false;
        nativeOwner.expired = true;
        assertThrows(RuntimeException.class, () -> confirm(start()));
    }

    @Test
    void finalizedGrantRetryPreservesGrantAndNeverRechecksEvidence() {
        var c = confirm(start());
        nativeOwner.unavailable = true;
        assertEquals(c.authorization(), confirm(c).authorization());
        assertEquals(1, nativeOwner.verifications.get());
    }

    @Test
    void journalBeforeGrantIssuanceCannotReconcile() {
        var c = confirm(start());
        var req = request(c);
        service.consume(c.caseId(), req).block();
        nativeOwner.journal =
                new ReproofingExecution(
                        c.caseId(),
                        req.executionId(),
                        binding,
                        "CREDENTIALS_REPLACED",
                        Instant.EPOCH);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.record(
                                        c.caseId(),
                                        new RecordReproofingExecutionRequest(
                                                binding,
                                                req.authorizationId(),
                                                req.executionId(),
                                                "CREDENTIALS_REPLACED"))
                                .block());
    }

    @Test
    void providerSessionCannotBeReboundToAnotherCase() {
        var c = start();
        binding = withNonce();
        var other = service.create(new CreateReproofingCaseRequest(binding)).block();
        assertThrows(
                RuntimeException.class,
                () ->
                        service.bindSession(
                                        other.caseId(),
                                        new BindReproofingSessionRequest(
                                                binding, c.providerSessionId()))
                                .block());
    }

    @Test void wrongScopeAndEveryImmutableTargetFieldAreRefused() {
        var c=start();
        String[][] changes={{"issuer","https://other.example.test/realms/customer"},{"realm","other"},{"tenant","other"},{"subject","other"},{"population","STAFF"},{"purpose","UNLOCK"},{"generation","other"},{"credentialRevision","other"},{"nonce","other"}};
        for(String[] change:changes){
            var values=new java.util.HashMap<String,Object>();
            values.put("issuer",binding.issuer());values.put("realm",binding.realm());values.put("tenant",binding.tenant());values.put("subject",binding.subject());values.put("partyId",binding.partyId());values.put("population",binding.population());values.put("purpose",binding.purpose());values.put("generation",binding.generation());values.put("credentialRevision",binding.credentialRevision());values.put("nonce",binding.nonce());
            values.put(change[0],change[1]);
            var changed=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(values,ReproofingBinding.class);
            assertThrows(RuntimeException.class,()->service.inspect(c.caseId(),new ConfirmReproofingRequest(changed)).block());
        }
        var otherParty=new ReproofingBinding(binding.issuer(),binding.realm(),binding.tenant(),binding.subject(),UUID.randomUUID(),binding.population(),binding.purpose(),binding.generation(),binding.credentialRevision(),binding.nonce());
        assertThrows(RuntimeException.class,()->service.inspect(c.caseId(),new ConfirmReproofingRequest(otherParty)).block());
    }
    @Test void sameProviderEvidenceCannotBeClaimedByAnotherCase() {
        nativeOwner.fixedEvidenceId="one-evidence-"+UUID.randomUUID();
        assertNotNull(confirm(start()).authorization());binding=withNonce();var other=start();
        assertThrows(RuntimeException.class,()->confirm(other));
        assertNull(service.inspect(other.caseId(),new ConfirmReproofingRequest(binding)).block().authorization());
    }
    @Test void rejectedProviderResultExhaustsOnlyOriginalCase() {
        nativeOwner.documentAuthentic=false;var c=start();
        for(int i=0;i<3;i++)assertNull(confirm(c).authorization());
        assertThrows(RuntimeException.class,()->confirm(c));
        assertEquals(3,nativeOwner.verifications.get());
        nativeOwner.documentAuthentic=true;binding=withNonce();assertNotNull(confirm(start()).authorization());
    }
    @Test void abandonedConfirmationReservationCannotOpenAnotherProviderOpportunity() {
        var c=start();
        db.sql("UPDATE identity_reproofing_case SET confirmation_id=:token,reserved_until=clock_timestamp()+interval '15 seconds' WHERE case_id=:id").bind("token",UUID.randomUUID()).bind("id",c.caseId()).fetch().rowsUpdated().block();
        assertThrows(RuntimeException.class,()->confirm(c));assertEquals(0,nativeOwner.verifications.get());
        db.sql("UPDATE identity_reproofing_case SET reserved_until=clock_timestamp()-interval '1 second' WHERE case_id=:id").bind("id",c.caseId()).fetch().rowsUpdated().block();
        var recovered=confirm(c);assertEquals(c.providerStartId(),recovered.providerStartId());assertEquals(c.providerSessionId(),recovered.providerSessionId());assertNotNull(recovered.authorization());
    }
    ReproofingBinding withNonce() {
        return new ReproofingBinding(
                binding.issuer(),
                binding.realm(),
                binding.tenant(),
                binding.subject(),
                binding.partyId(),
                binding.population(),
                binding.purpose(),
                binding.generation(),
                binding.credentialRevision(),
                UUID.randomUUID().toString());
    }

    static class Fixture implements ReproofingNativePort, ReproofingEvidencePort {
        boolean eligible = true,
                unavailable,
                wrongReference,
                wrongSession,
                expired,
                documentAuthentic = true;
        AtomicInteger verifications = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        ReproofingExecution journal;
        String fixedEvidenceId;

        public Mono<ReproofingAdmission> prepare(ReproofingNativeRequest r) {
            return Mono.just(
                    new ReproofingAdmission(r.binding(), true, Instant.now().plusSeconds(600)));
        }

        public Mono<ReproofingAdmission> admit(ReproofingNativeRequest r) {
            admissions.incrementAndGet();
            return Mono.just(
                    new ReproofingAdmission(r.binding(), eligible, Instant.now().plusSeconds(100)));
        }

        public Mono<ReproofingExecution> journal(ReproofingNativeRequest r) {
            return Mono.justOrEmpty(journal);
        }

        public Mono<VerifiedReproofingEvidence> verify(ReproofingEvidenceRequest r) {
            verifications.incrementAndGet();
            if (unavailable) return Mono.error(new IllegalStateException("Fixture outage"));
            return Mono.just(
                    new VerifiedReproofingEvidence(
                            r.caseId(),
                            r.binding(),
                            r.providerId(),
                            r.policyId(),
                            wrongSession ? "other" : r.providerSessionId(),
                            fixedEvidenceId==null?"evidence-" + r.caseId():fixedEvidenceId,
                            "sha256:" + "a".repeat(64),
                            wrongReference ? UUID.randomUUID() : r.binding().partyId(),
                            Instant.now().minusSeconds(1),
                            Instant.now().plusSeconds(expired ? -1 : 90),
                            documentAuthentic,
                            true,
                            true,
                            !wrongReference));
        }
    }
}
