package io.fireflysoftware.core.kycb.core.reproofing.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingEvidencePort;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingNativePort;
import io.fireflysoftware.core.kycb.core.reproofing.ReproofingService;
import io.fireflysoftware.core.kycb.interfaces.reproofing.*;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Database owns every reservation, expiry, budget, grant and execution transition. */
public final class ReproofingServiceImpl implements ReproofingService {
    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final ReproofingNativePort nativeOwner;
    private final ReproofingEvidencePort evidenceOwner;
    private final String provider;
    private final String policy;
    private final String issuer;
    private final String realm;
    private final String tenant;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReproofingServiceImpl(DatabaseClient database, TransactionalOperator transactions,
            ReproofingNativePort nativeOwner, ReproofingEvidencePort evidenceOwner, String provider,
            String policy, String issuer, String realm, String tenant) {
        this.database = database;
        this.transactions = transactions;
        this.nativeOwner = nativeOwner;
        this.evidenceOwner = evidenceOwner;
        this.provider = text(provider, 512);
        this.policy = text(policy, 512);
        this.issuer = text(issuer, 512);
        this.realm = text(realm, 512);
        this.tenant = text(tenant, 512);
        URI uri = URI.create(issuer);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("HTTPS issuer required");
        }
    }

    @Override
    public Mono<ReproofingCaseResponse> create(CreateReproofingCaseRequest request) {
        return Mono.defer(() -> {
            ReproofingBinding binding = request.binding();
            validate(binding);
            return nativeOwner.prepare(new ReproofingNativeRequest(binding, null, null))
                    .switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(admission -> transaction(() -> now().flatMap(databaseNow ->
                            initialize(binding, admission, databaseNow))));
        });
    }

    private Mono<ReproofingCaseResponse> initialize(ReproofingBinding binding,
            ReproofingAdmission admission, Instant databaseNow) {
        admission(admission, binding, databaseNow, false);
        Instant expiry = earliest(admission.expiresAt(), databaseNow.plusSeconds(600));
        String nonceKey = digest(binding.issuer(), binding.nonce());
        return database.sql("""
                INSERT INTO identity_reproofing_case
                  (case_id,provider_start_id,binding,issuer_nonce_key,subject_budget_key,
                   status,provider_id,policy_id,expires_at)
                VALUES(:id,:start,:binding,:key,:budget,'PENDING',:provider,:policy,:expiry)
                ON CONFLICT(issuer_nonce_key) DO NOTHING
                """)
                .bind("id", UUID.randomUUID()).bind("start", UUID.randomUUID())
                .bind("binding", encode(binding)).bind("key", nonceKey)
                .bind("budget", digest(binding.issuer(), binding.subject(), binding.generation()))
                .bind("provider", provider).bind("policy", policy).bind("expiry", utc(expiry))
                .fetch().rowsUpdated()
                .then(database.sql("""
                        SELECT * FROM identity_reproofing_case
                        WHERE issuer_nonce_key=:key FOR UPDATE
                        """).bind("key", nonceKey).fetch().one())
                .map(StateImpl::new)
                .flatMap(state -> {
                    if (!state.binding().equals(binding)) {
                        throw denied();
                    }
                    live(state, databaseNow);
                    return audit(state.id(), "CREATED").thenReturn(state.response(databaseNow));
                });
    }

    @Override
    public Mono<ReproofingCaseResponse> inspect(UUID caseId, ConfirmReproofingRequest request) {
        return load(caseId, request.binding(), false)
                .flatMap(state -> now().map(state::response));
    }

    @Override
    public Mono<ReproofingCaseResponse> bindSession(UUID caseId, BindReproofingSessionRequest request) {
        return locked(caseId, request.binding(), (state, databaseNow) -> {
            live(state, databaseNow);
            text(request.providerSessionId(), 512);
            if (state.session() != null) {
                if (!state.session().equals(request.providerSessionId())) {
                    throw denied();
                }
                return Mono.just(state.response(databaseNow));
            }
            if (!"PENDING".equals(state.status())) {
                throw denied();
            }
            return database.sql("""
                    UPDATE identity_reproofing_case SET provider_session_id=:session
                    WHERE case_id=:id
                    """).bind("id", caseId).bind("session", request.providerSessionId())
                    .fetch().rowsUpdated().then(audit(caseId, "SESSION_BOUND"))
                    .then(load(caseId, request.binding(), false))
                    .map(updated -> updated.response(databaseNow));
        });
    }

    @Override
    public Mono<ReproofingCaseResponse> confirm(UUID caseId, ConfirmReproofingRequest request) {
        return Mono.defer(() -> {
            UUID reservation = UUID.randomUUID();
            return reserve(caseId, request.binding(), reservation).flatMap(state -> {
                if (state.authorization() != null) {
                    return now().map(state::response);
                }
                var evidenceRequest = new ReproofingEvidenceRequest(state.id(), state.binding(),
                        state.provider(), state.policy(), state.session());
                return evidenceOwner.verify(evidenceRequest)
                        .switchIfEmpty(Mono.error(unavailable()))
                        .flatMap(evidence -> finalizeNegative(caseId, request.binding(),
                                reservation, evidence).switchIfEmpty(Mono.defer(() ->
                                        nativeOwner.admit(new ReproofingNativeRequest(
                                                        state.binding(), state.id(), null))
                                                .switchIfEmpty(Mono.error(unavailable()))
                                                .flatMap(admission -> finishConfirmation(caseId,
                                                        request.binding(), reservation,
                                                        evidence, admission)))))
                        .onErrorResume(error -> release(caseId, reservation).then(Mono.error(
                                error instanceof ResponseStatusException ? error : unavailable())));
            });
        });
    }

    private Mono<StateImpl> reserve(UUID caseId, ReproofingBinding binding, UUID reservation) {
        return locked(caseId, binding, (state, databaseNow) -> {
            live(state, databaseNow);
            if (state.authorization() != null) {
                if (!databaseNow.isBefore(state.grantExpiry())) {
                    throw denied();
                }
                return Mono.just(state);
            }
            if (state.session() == null || !"PENDING".equals(state.status()) || state.failures() >= 3) {
                throw denied();
            }
            if (state.reservedUntil() != null && databaseNow.isBefore(state.reservedUntil())) {
                throw unavailable();
            }
            return database.sql("""
                    UPDATE identity_reproofing_case
                    SET confirmation_id=:token,reserved_until=clock_timestamp()+interval '15 seconds'
                    WHERE case_id=:id
                    """).bind("token", reservation).bind("id", caseId).fetch().rowsUpdated()
                    .then(load(caseId, binding, false));
        });
    }

    private Mono<ReproofingCaseResponse> finalizeNegative(UUID caseId, ReproofingBinding binding,
            UUID reservation, VerifiedReproofingEvidence evidence) {
        return locked(caseId, binding, (state, databaseNow) -> {
            confirmation(state, reservation, evidence, databaseNow);
            if (completeProof(binding, evidence)) {
                // Commit the assessment before requesting independent native admission.
                return Mono.empty();
            }
            return reject(state, reservation, false, databaseNow);
        });
    }

    private Mono<ReproofingCaseResponse> finishConfirmation(UUID caseId, ReproofingBinding binding,
            UUID reservation, VerifiedReproofingEvidence evidence, ReproofingAdmission admission) {
        return locked(caseId, binding, (state, databaseNow) -> {
            confirmation(state, reservation, evidence, databaseNow);
            admission(admission, binding, databaseNow, true);
            if (admission.expiresAt().isAfter(state.expiry()) || !completeProof(binding, evidence)) {
                throw denied();
            }
            return lockedBudget(state.budgetKey()).flatMap(verifiedGrants -> {
                if (verifiedGrants < 3) {
                    return grant(state, evidence, admission, databaseNow);
                }
                return reject(state, reservation, true, databaseNow);
            });
        });
    }

    private void confirmation(StateImpl state, UUID reservation, VerifiedReproofingEvidence evidence,
            Instant databaseNow) {
        live(state, databaseNow);
        if (!"PENDING".equals(state.status()) || state.authorization() != null
                || !reservation.equals(state.confirmation()) || state.reservedUntil() == null
                || !databaseNow.isBefore(state.reservedUntil())
                || !envelope(state, evidence, databaseNow)) {
            throw denied();
        }
    }

    private boolean completeProof(ReproofingBinding binding, VerifiedReproofingEvidence evidence) {
        return evidence.referenceMatched() && binding.partyId().equals(evidence.referenceId())
                && evidence.documentAuthentic() && evidence.liveMatch() && evidence.fraudCleared();
    }

    private Mono<Integer> lockedBudget(String budgetKey) {
        return database.sql("""
                INSERT INTO identity_reproofing_budget(subject_budget_key,verified_grants)
                VALUES(:key,0) ON CONFLICT DO NOTHING
                """).bind("key", budgetKey).fetch().rowsUpdated()
                .then(database.sql("""
                        SELECT verified_grants FROM identity_reproofing_budget
                        WHERE subject_budget_key=:key FOR UPDATE
                        """).bind("key", budgetKey)
                        .map((row, metadata) -> row.get("verified_grants", Integer.class)).one());
    }

    private Mono<ReproofingCaseResponse> grant(StateImpl state, VerifiedReproofingEvidence evidence,
            ReproofingAdmission admission, Instant databaseNow) {
        Instant expiry = earliest(databaseNow.plusSeconds(120), state.expiry(),
                evidence.expiresAt(), admission.expiresAt());
        return database.sql("""
                UPDATE identity_reproofing_budget SET verified_grants=verified_grants+1
                WHERE subject_budget_key=:key
                """).bind("key", state.budgetKey()).fetch().rowsUpdated()
                .then(database.sql("""
                        UPDATE identity_reproofing_case
                        SET status='VERIFIED',authorization_id=:authorization,execution_id=:execution,
                            evidence_id=:evidence,evidence_digest=:digest,grant_expires_at=:expiry,
                            grant_issued_at=clock_timestamp(),confirmation_id=NULL,reserved_until=NULL
                        WHERE case_id=:id
                        """).bind("id", state.id()).bind("authorization", UUID.randomUUID())
                        .bind("execution", UUID.randomUUID()).bind("evidence", evidence.evidenceId())
                        .bind("digest", evidence.evidenceDigest()).bind("expiry", utc(expiry))
                        .fetch().rowsUpdated())
                .then(audit(state.id(), "VERIFIED"))
                .then(load(state.id(), state.binding(), false))
                .map(updated -> updated.response(databaseNow));
    }

    private Mono<ReproofingCaseResponse> reject(StateImpl state, UUID reservation,
            boolean exhausted, Instant databaseNow) {
        return database.sql("""
                UPDATE identity_reproofing_case SET failures=failures+1,
                    status=CASE WHEN failures>=2 OR :exhausted THEN 'DENIED' ELSE 'PENDING' END,
                    confirmation_id=NULL,reserved_until=NULL WHERE case_id=:id
                """).bind("exhausted", exhausted).bind("id", state.id()).fetch().rowsUpdated()
                .then(audit(state.id(), "CONFIRMATION_FAILED_" + reservation))
                .then(load(state.id(), state.binding(), false))
                .map(updated -> updated.response(databaseNow));
    }

    private Mono<Void> release(UUID caseId, UUID reservation) {
        return database.sql("""
                UPDATE identity_reproofing_case SET confirmation_id=NULL,reserved_until=NULL
                WHERE case_id=:id AND confirmation_id=:token
                """).bind("id", caseId).bind("token", reservation).fetch().rowsUpdated().then();
    }

    @Override
    public Mono<ReproofingAuthorization> consume(UUID caseId, ReproofingAuthorizationRequest request) {
        return Mono.defer(() -> {
            validate(request.binding());
            return nativeOwner.admit(new ReproofingNativeRequest(
                            request.binding(), caseId, request.executionId()))
                    .switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(admission -> locked(caseId, request.binding(), (state, databaseNow) -> {
                        admission(admission, request.binding(), databaseNow, true);
                        if (admission.expiresAt().isAfter(state.expiry())) {
                            throw denied();
                        }
                        authorization(state, request, databaseNow, false);
                        if ("CONSUMED".equals(state.status())) {
                            return Mono.just(state.grant());
                        }
                        return database.sql("""
                                UPDATE identity_reproofing_case SET status='CONSUMED'
                                WHERE case_id=:id
                                """).bind("id", caseId).fetch().rowsUpdated()
                                .then(audit(caseId, "CONSUMED")).thenReturn(state.grant());
                    }));
        });
    }

    @Override
    public Mono<ReproofingAuthorization> verify(UUID caseId, ReproofingAuthorizationRequest request) {
        return load(caseId, request.binding(), false).flatMap(state -> now().map(databaseNow -> {
            authorization(state, request, databaseNow, true);
            return state.grant();
        }));
    }

    @Override
    public Mono<ReproofingCaseResponse> record(UUID caseId, RecordReproofingExecutionRequest request) {
        return Mono.defer(() -> {
            validate(request.binding());
            if (!"CREDENTIALS_REPLACED".equals(request.outcome())) {
                throw denied();
            }
            return nativeOwner.journal(new ReproofingNativeRequest(
                            request.binding(), caseId, request.executionId()))
                    .switchIfEmpty(Mono.error(denied()))
                    .flatMap(journal -> locked(caseId, request.binding(), (state, databaseNow) -> {
                        validateJournal(state, request, journal, databaseNow);
                        return database.sql("""
                                UPDATE identity_reproofing_case SET status='EXECUTED'
                                WHERE case_id=:id
                                """).bind("id", caseId).fetch().rowsUpdated()
                                .then(audit(caseId, "EXECUTED"))
                                .then(load(caseId, request.binding(), false))
                                .map(updated -> updated.response(databaseNow));
                    }));
        });
    }

    private void validateJournal(StateImpl state, RecordReproofingExecutionRequest request,
            ReproofingExecution journal, Instant databaseNow) {
        if (!Set.of("CONSUMED", "EXECUTED").contains(state.status())
                || !Objects.equals(state.authorization(), request.authorizationId())
                || !Objects.equals(state.execution(), request.executionId())
                || !state.id().equals(journal.caseId())
                || !state.execution().equals(journal.executionId())
                || !state.binding().equals(journal.binding())
                || !request.outcome().equals(journal.outcome()) || journal.committedAt() == null
                || journal.committedAt().isBefore(state.grantIssuedAt())
                || journal.committedAt().isAfter(databaseNow)
                || journal.committedAt().isAfter(state.grantExpiry())) {
            throw denied();
        }
    }

    private boolean envelope(StateImpl state, VerifiedReproofingEvidence evidence, Instant databaseNow) {
        return evidence != null && state.id().equals(evidence.caseId())
                && state.binding().equals(evidence.binding()) && state.provider().equals(evidence.providerId())
                && state.policy().equals(evidence.policyId()) && state.session().equals(evidence.providerSessionId())
                && evidence.verifiedAt() != null && !evidence.verifiedAt().isAfter(databaseNow)
                && evidence.verifiedAt().isAfter(databaseNow.minusSeconds(600))
                && evidence.expiresAt() != null && databaseNow.isBefore(evidence.expiresAt())
                && !evidence.expiresAt().isAfter(state.expiry())
                && bounded(evidence.evidenceId(), 512) && bounded(evidence.evidenceDigest(), 512);
    }

    private void authorization(StateImpl state, ReproofingAuthorizationRequest request,
            Instant databaseNow, boolean consumed) {
        live(state, databaseNow);
        if (state.authorization() == null || !state.authorization().equals(request.authorizationId())
                || !state.execution().equals(request.executionId()) || !databaseNow.isBefore(state.grantExpiry())
                || (consumed ? !"CONSUMED".equals(state.status())
                        : !Set.of("VERIFIED", "CONSUMED").contains(state.status()))) {
            throw denied();
        }
    }

    private void validate(ReproofingBinding binding) {
        if (binding == null || binding.partyId() == null || !issuer.equals(binding.issuer())
                || !realm.equals(binding.realm()) || !tenant.equals(binding.tenant())
                || !"HUMAN".equals(binding.population())
                || !"CREDENTIAL_REPLACEMENT".equals(binding.purpose())) {
            throw denied();
        }
        text(binding.subject(), 512);
        text(binding.generation(), 512);
        text(binding.credentialRevision(), 512);
        text(binding.nonce(), 128);
    }

    private void live(StateImpl state, Instant databaseNow) {
        if (!databaseNow.isBefore(state.expiry()) || "DENIED".equals(state.status())) {
            throw denied();
        }
    }

    private void admission(ReproofingAdmission admission, ReproofingBinding binding,
            Instant databaseNow, boolean eligible) {
        if (admission == null || !binding.equals(admission.binding()) || admission.expiresAt() == null
                || !databaseNow.isBefore(admission.expiresAt()) || eligible && !admission.eligible()) {
            throw denied();
        }
    }

    private <T> Mono<T> locked(UUID caseId, ReproofingBinding binding,
            BiFunction<StateImpl, Instant, Mono<T>> work) {
        return transaction(() -> load(caseId, binding, true)
                .flatMap(state -> now().flatMap(databaseNow -> work.apply(state, databaseNow))));
    }

    private <T> Mono<T> transaction(Supplier<Mono<T>> work) {
        return transactions.transactional(Mono.defer(work));
    }

    private Mono<Instant> now() {
        return database.sql("SELECT clock_timestamp() AS now")
                .map((row, metadata) -> row.get("now", OffsetDateTime.class).toInstant()).one();
    }

    private Mono<StateImpl> load(UUID caseId, ReproofingBinding binding, boolean lock) {
        validate(binding);
        if (caseId == null) {
            return Mono.error(denied());
        }
        return database.sql("SELECT * FROM identity_reproofing_case WHERE case_id=:id"
                        + (lock ? " FOR UPDATE" : ""))
                .bind("id", caseId).fetch().one().switchIfEmpty(Mono.error(denied()))
                .map(row -> {
                    StateImpl state = new StateImpl(row);
                    if (!state.binding().equals(binding)) {
                        throw denied();
                    }
                    return state;
                });
    }

    private Mono<Void> audit(UUID caseId, String event) {
        return database.sql("""
                INSERT INTO identity_reproofing_audit(case_id,event)
                VALUES(:id,:event) ON CONFLICT DO NOTHING
                """).bind("id", caseId).bind("event", event).fetch().rowsUpdated().then();
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String text(String value, int maximum) {
        if (!bounded(value, maximum)) {
            throw denied();
        }
        return value;
    }

    private static Instant earliest(Instant... times) {
        Instant earliest = times[0];
        for (Instant candidate : times) {
            if (candidate.isBefore(earliest)) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private ReproofingBinding decode(String value) {
        try {
            return json.readValue(value, ReproofingBinding.class);
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private String digest(String... values) {
        try {
            var calculator = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                calculator.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                calculator.update(bytes);
            }
            return HexFormat.of().formatHex(calculator.digest());
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Recovery refused");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recovery unavailable");
    }

    private final class StateImpl {
        private final Map<String, Object> row;

        StateImpl(Map<String, Object> row) { this.row = row; }
        String string(String key) { return (String) row.get(key); }
        UUID uuid(String key) { return (UUID) row.get(key); }
        Instant instant(String key) {
            var value = row.get(key);
            return value == null ? null : ((OffsetDateTime) value).toInstant();
        }
        UUID id() { return uuid("case_id"); }
        UUID authorization() { return uuid("authorization_id"); }
        UUID execution() { return uuid("execution_id"); }
        UUID confirmation() { return uuid("confirmation_id"); }
        Instant expiry() { return instant("expires_at"); }
        Instant grantExpiry() { return instant("grant_expires_at"); }
        Instant grantIssuedAt() { return instant("grant_issued_at"); }
        Instant reservedUntil() { return instant("reserved_until"); }
        String status() { return string("status"); }
        String provider() { return string("provider_id"); }
        String policy() { return string("policy_id"); }
        String session() { return string("provider_session_id"); }
        String budgetKey() { return string("subject_budget_key"); }
        int failures() { return (Integer) row.get("failures"); }
        ReproofingBinding binding() { return decode(string("binding")); }

        ReproofingAuthorization grant() {
            return authorization() == null ? null : new ReproofingAuthorization(id(), authorization(),
                    execution(), binding(), string("evidence_digest"), policy(), grantExpiry());
        }

        ReproofingCaseResponse response(Instant databaseNow) {
            boolean expiredGrant = grantExpiry() != null && !databaseNow.isBefore(grantExpiry());
            boolean expired = !"EXECUTED".equals(status())
                    && (!databaseNow.isBefore(expiry()) || expiredGrant);
            return new ReproofingCaseResponse(id(), uuid("provider_start_id"), binding(),
                    expired ? "EXPIRED" : status(), provider(), policy(), session(), expiry(),
                    expired || expiredGrant ? null : grant());
        }
    }
}
