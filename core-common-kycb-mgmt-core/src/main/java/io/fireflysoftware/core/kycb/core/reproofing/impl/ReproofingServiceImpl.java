package io.fireflysoftware.core.kycb.core.reproofing.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.fireflysoftware.core.kycb.core.reproofing.*;
import io.fireflysoftware.core.kycb.interfaces.reproofing.*;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Database owns every reservation, expiry, budget, grant and execution transition. */
public final class ReproofingServiceImpl implements ReproofingService {
    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final ReproofingNativePort nativeOwner;
    private final ReproofingEvidencePort evidenceOwner;
    private final String provider, policy, issuer, realm, tenant;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReproofingServiceImpl(
            DatabaseClient database,
            TransactionalOperator transactions,
            ReproofingNativePort nativeOwner,
            ReproofingEvidencePort evidenceOwner,
            String provider,
            String policy,
            String issuer,
            String realm,
            String tenant) {
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
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("HTTPS issuer required");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Recovery refused");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recovery unavailable");
    }

    private static String text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw denied();
        return value;
    }

    private void validate(ReproofingBinding binding) {
        if (binding == null
                || binding.partyId() == null
                || !issuer.equals(binding.issuer())
                || !realm.equals(binding.realm())
                || !tenant.equals(binding.tenant())
                || !"HUMAN".equals(binding.population())
                || !"CREDENTIAL_REPLACEMENT".equals(binding.purpose())) throw denied();
        text(binding.subject(), 512);
        text(binding.generation(), 512);
        text(binding.credentialRevision(), 512);
        text(binding.nonce(), 128);
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
            var digestCalculator = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digestCalculator.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digestCalculator.update(bytes);
            }
            return HexFormat.of().formatHex(digestCalculator.digest());
        } catch (Exception error) {
            throw unavailable();
        }
    }

    private <T> Mono<T> transaction(Supplier<Mono<T>> work) {
        return transactions.transactional(Mono.defer(work));
    }

    private Mono<Instant> now() {
        return database.sql("SELECT clock_timestamp() AS now")
                .map((databaseRow, metadata) -> databaseRow.get("now", OffsetDateTime.class).toInstant())
                .one();
    }

    private Mono<State> load(UUID id, ReproofingBinding binding, boolean lock) {
        validate(binding);
        if (id == null) return Mono.error(denied());
        return database.sql(
                        "SELECT * FROM identity_reproofing_case WHERE case_id=:id"
                                + (lock ? " FOR UPDATE" : ""))
                .bind("id", id)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(denied()))
                .map(row -> {
                    State state = new State(row);
                    if (!state.binding().equals(binding)) throw denied();
                    return state;
                });
    }

    private Mono<Void> audit(UUID id, String event) {
        return database.sql(
                        "INSERT INTO identity_reproofing_audit(case_id,event) VALUES(:id,:event) ON"
                            + " CONFLICT DO NOTHING")
                .bind("id", id)
                .bind("event", event)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private void live(State state, Instant databaseNow) {
        if (!databaseNow.isBefore(state.expiry()) || "DENIED".equals(state.status())) throw denied();
    }

    private void admission(
            ReproofingAdmission nativeAdmission, ReproofingBinding binding, Instant databaseNow, boolean eligible) {
        if (nativeAdmission == null
                || !binding.equals(nativeAdmission.binding())
                || nativeAdmission.expiresAt() == null
                || !databaseNow.isBefore(nativeAdmission.expiresAt())
                || eligible && !nativeAdmission.eligible()) throw denied();
    }

    @Override
    public Mono<ReproofingCaseResponse> create(CreateReproofingCaseRequest request) {
        return Mono.defer(() -> {
            ReproofingBinding binding = request.binding();
            validate(binding);
            return nativeOwner
                    .prepare(new ReproofingNativeRequest(binding, null, null))
                    .switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(nativeAdmission ->
                            transaction(() ->
                                    now().flatMap(databaseNow -> {
                                        admission(nativeAdmission, binding, databaseNow, false);
                                        UUID id = UUID.randomUUID();
                                        Instant expiry =
                                                nativeAdmission.expiresAt().isBefore(databaseNow.plusSeconds(600))
                                                        ? nativeAdmission.expiresAt()
                                                        : databaseNow.plusSeconds(600);
                                        String key = digest(binding.issuer(), binding.nonce());
                                        return database.sql(
                                                        "INSERT INTO"
                                                            + " identity_reproofing_case(case_id,provider_start_id,binding,issuer_nonce_key,subject_budget_key,status,provider_id,policy_id,expires_at)"
                                                            + " VALUES(:id,:start,:binding,:key,:budget,'PENDING',:provider,:policy,:expiry)"
                                                            + " ON CONFLICT(issuer_nonce_key) DO"
                                                            + " NOTHING")
                                                .bind("id", id)
                                                .bind("start", UUID.randomUUID())
                                                .bind("binding", encode(binding))
                                                .bind("key", key)
                                                .bind(
                                                        "budget",
                                                        digest(
                                                                binding.issuer(),
                                                                binding.subject(),
                                                                binding.generation()))
                                                .bind("provider", provider)
                                                .bind("policy", policy)
                                                .bind(
                                                        "expiry",
                                                        OffsetDateTime.ofInstant(
                                                                expiry, java.time.ZoneOffset.UTC))
                                                .fetch()
                                                .rowsUpdated()
                                                .then(
                                                        database.sql(
                                                                        "SELECT * FROM"
                                                                            + " identity_reproofing_case"
                                                                            + " WHERE"
                                                                            + " issuer_nonce_key=:key"
                                                                            + " FOR UPDATE")
                                                                .bind("key", key)
                                                                .fetch()
                                                                .one())
                                                .map(State::new)
                                                .flatMap(state -> {
                                                    if (!state.binding().equals(binding)) throw denied();
                                                    live(state, databaseNow);
                                                    return audit(state.id(), "CREATED")
                                                            .thenReturn(state.response(databaseNow));
                                                });
                                    })));
        });
    }

    @Override
    public Mono<ReproofingCaseResponse> inspect(UUID id, ConfirmReproofingRequest request) {
        return load(id, request.binding(), false).flatMap(state -> now().map(state::response));
    }

    @Override
    public Mono<ReproofingCaseResponse> bindSession(UUID id, BindReproofingSessionRequest request) {
        return transaction(() ->
                load(id, request.binding(), true)
                        .flatMap(state ->
                                now().flatMap(databaseNow -> {
                                    live(state, databaseNow);
                                    text(request.providerSessionId(), 512);
                                    if (state.session() != null) {
                                        if (!state.session().equals(request.providerSessionId()))
                                            throw denied();
                                        return Mono.just(state.response(databaseNow));
                                    }
                                    if (!"PENDING".equals(state.status())) throw denied();
                                    return database.sql(
                                                    "UPDATE identity_reproofing_case SET"
                                                        + " provider_session_id=:session WHERE"
                                                        + " case_id=:id")
                                            .bind("id", id)
                                            .bind("session", request.providerSessionId())
                                            .fetch()
                                            .rowsUpdated()
                                            .then(audit(id, "SESSION_BOUND"))
                                            .then(load(id, request.binding(), false))
                                            .map(value -> value.response(databaseNow));
                                })));
    }

    @Override
    public Mono<ReproofingCaseResponse> confirm(UUID id, ConfirmReproofingRequest request) {
        return Mono.defer(() -> {
            UUID reservation = UUID.randomUUID();
            return transaction(() ->
                            load(id, request.binding(), true)
                                    .flatMap(state ->
                                            now().flatMap(databaseNow -> {
                                                live(state, databaseNow);
                                                if (state.authorization() != null) {
                                                    if (!databaseNow.isBefore(state.grantExpiry()))
                                                        throw denied();
                                                    return Mono.just(state);
                                                }
                                                if (state.session() == null
                                                        || !"PENDING".equals(state.status())
                                                        || state.failures() >= 3) throw denied();
                                                if (state.reservedUntil() != null
                                                        && databaseNow.isBefore(state.reservedUntil()))
                                                    throw unavailable();
                                                return database.sql(
                                                                "UPDATE identity_reproofing_case"
                                                                    + " SET confirmation_id=:token,reserved_until=clock_timestamp()+interval"
                                                                    + " '15 seconds' WHERE"
                                                                    + " case_id=:id")
                                                        .bind("token", reservation)
                                                        .bind("id", id)
                                                        .fetch()
                                                        .rowsUpdated()
                                                        .then(load(id, request.binding(), false));
                                            })))
                    .flatMap(state -> {
                        if (state.authorization() != null) return now().map(state::response);
                        var evidenceRequest =
                                new ReproofingEvidenceRequest(
                                        state.id(), state.binding(), state.provider(), state.policy(), state.session());
                        return evidenceOwner
                                .verify(evidenceRequest)
                                .switchIfEmpty(Mono.error(unavailable()))
                                .flatMap(evidence ->
                                        nativeOwner
                                                .admit(
                                                        new ReproofingNativeRequest(
                                                                state.binding(), state.id(), null))
                                                .switchIfEmpty(Mono.error(unavailable()))
                                                .flatMap(nativeAdmission ->
                                                        finishConfirmation(
                                                                id,
                                                                request.binding(),
                                                                reservation,
                                                                evidence,
                                                                nativeAdmission)))
                                .onErrorResume(error ->
                                        release(id, reservation)
                                                .then(
                                                        Mono.error(
                                                                error
                                                                                instanceof
                                                                                ResponseStatusException
                                                                        ? error
                                                                        : unavailable())));
                    });
        });
    }

    private Mono<Void> release(UUID id, UUID reservation) {
        return database.sql(
                        "UPDATE identity_reproofing_case SET"
                            + " confirmation_id=NULL,reserved_until=NULL WHERE case_id=:id AND"
                            + " confirmation_id=:token")
                .bind("id", id)
                .bind("token", reservation)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private boolean envelope(State state, VerifiedReproofingEvidence evidence, Instant databaseNow) {
        return evidence != null
                && state.id().equals(evidence.caseId())
                && state.binding().equals(evidence.binding())
                && state.provider().equals(evidence.providerId())
                && state.policy().equals(evidence.policyId())
                && state.session().equals(evidence.providerSessionId())
                && evidence.verifiedAt() != null
                && !evidence.verifiedAt().isAfter(databaseNow)
                && evidence.verifiedAt().isAfter(databaseNow.minusSeconds(600))
                && evidence.expiresAt() != null
                && databaseNow.isBefore(evidence.expiresAt())
                && !evidence.expiresAt().isAfter(state.expiry())
                && evidence.evidenceId() != null
                && !evidence.evidenceId().isBlank()
                && evidence.evidenceId().length() <= 512
                && evidence.evidenceDigest() != null
                && !evidence.evidenceDigest().isBlank()
                && evidence.evidenceDigest().length() <= 512;
    }

    private Mono<ReproofingCaseResponse> finishConfirmation(
            UUID id,
            ReproofingBinding binding,
            UUID reservation,
            VerifiedReproofingEvidence evidence,
            ReproofingAdmission nativeAdmission) {
        return transaction(() ->
                load(id, binding, true)
                        .flatMap(state ->
                                now().flatMap(databaseNow -> {
                                    live(state, databaseNow);
                                    admission(nativeAdmission, binding, databaseNow, true);
                                    if (nativeAdmission.expiresAt().isAfter(state.expiry())) throw denied();
                                    if (!reservation.equals(state.confirmation())
                                            || state.reservedUntil() == null
                                            || !databaseNow.isBefore(state.reservedUntil())) throw denied();
                                    boolean matched =
                                            envelope(state, evidence, databaseNow)
                                                    && evidence.referenceMatched()
                                                    && binding.partyId()
                                                            .equals(evidence.referenceId());
                                    boolean passed =
                                            matched
                                                    && evidence.documentAuthentic()
                                                    && evidence.liveMatch()
                                                    && evidence.fraudCleared();
                                    Mono<Integer> budget =
                                            passed
                                                    ? database.sql(
                                                                    "INSERT INTO"
                                                                        + " identity_reproofing_budget(subject_budget_key,verified_grants)"
                                                                        + " VALUES(:key,0) ON"
                                                                        + " CONFLICT DO NOTHING")
                                                            .bind("key", state.budgetKey())
                                                            .fetch()
                                                            .rowsUpdated()
                                                            .then(
                                                                    database.sql(
                                                                                    "SELECT"
                                                                                        + " verified_grants"
                                                                                        + " FROM"
                                                                                        + " identity_reproofing_budget"
                                                                                        + " WHERE"
                                                                                        + " subject_budget_key=:key"
                                                                                        + " FOR UPDATE")
                                                                            .bind(
                                                                                    "key",
                                                                                    state.budgetKey())
                                                                            .map((databaseRow, metadata) ->
                                                                                    databaseRow.get(
                                                                                            "verified_grants",
                                                                                            Integer
                                                                                                    .class))
                                                                            .one())
                                                    : Mono.just(0);
                                    return budget.flatMap(failures -> {
                                        if (passed && failures < 3) {
                                            Instant expiry = databaseNow.plusSeconds(120);
                                            if (expiry.isAfter(state.expiry())) expiry = state.expiry();
                                            if (expiry.isAfter(evidence.expiresAt()))
                                                expiry = evidence.expiresAt();
                                            if (expiry.isAfter(nativeAdmission.expiresAt()))
                                                expiry = nativeAdmission.expiresAt();
                                            return database.sql(
                                                            "UPDATE identity_reproofing_budget SET"
                                                                + " verified_grants=verified_grants+1"
                                                                + " WHERE subject_budget_key=:key")
                                                    .bind("key", state.budgetKey())
                                                    .fetch()
                                                    .rowsUpdated()
                                                    .then(
                                                            database.sql(
                                                                            "UPDATE"
                                                                                + " identity_reproofing_case"
                                                                                + " SET status='VERIFIED',authorization_id=:authorization,execution_id=:execution,evidence_id=:evidence,evidence_digest=:digest,grant_expires_at=:expiry,grant_issued_at=clock_timestamp(),confirmation_id=NULL,reserved_until=NULL"
                                                                                + " WHERE"
                                                                                + " case_id=:id")
                                                                    .bind("id", id)
                                                                    .bind(
                                                                            "authorization",
                                                                            UUID.randomUUID())
                                                                    .bind(
                                                                            "execution",
                                                                            UUID.randomUUID())
                                                                    .bind(
                                                                            "evidence",
                                                                            evidence.evidenceId())
                                                                    .bind(
                                                                            "digest",
                                                                            evidence
                                                                                    .evidenceDigest())
                                                                    .bind(
                                                                            "expiry",
                                                                            OffsetDateTime
                                                                                    .ofInstant(
                                                                                            expiry,
                                                                                            java
                                                                                                    .time
                                                                                                    .ZoneOffset
                                                                                                    .UTC))
                                                                    .fetch()
                                                                    .rowsUpdated())
                                                    .then(audit(id, "VERIFIED"))
                                                    .then(load(id, binding, false))
                                                    .map(value -> value.response(databaseNow));
                                        }
                                        return database.sql(
                                                        "UPDATE identity_reproofing_case SET"
                                                            + " failures=failures+1,status=CASE"
                                                            + " WHEN failures>=2 OR :exhausted THEN"
                                                            + " 'DENIED' ELSE 'PENDING'"
                                                            + " END,confirmation_id=NULL,reserved_until=NULL"
                                                            + " WHERE case_id=:id")
                                                .bind("exhausted", failures >= 3)
                                                .bind("id", id)
                                                .fetch()
                                                .rowsUpdated()
                                                .then(
                                                        audit(
                                                                id,
                                                                "CONFIRMATION_FAILED_"
                                                                        + reservation))
                                                .then(load(id, binding, false))
                                                .map(value -> value.response(databaseNow));
                                    });
                                })));
    }

    private void authorization(
            State state, ReproofingAuthorizationRequest databaseRow, Instant databaseNow, boolean consumed) {
        live(state, databaseNow);
        if (state.authorization() == null
                || !state.authorization().equals(databaseRow.authorizationId())
                || !state.execution().equals(databaseRow.executionId())
                || !databaseNow.isBefore(state.grantExpiry())
                || (consumed
                        ? !"CONSUMED".equals(state.status())
                        : !java.util.Set.of("VERIFIED", "CONSUMED").contains(state.status())))
            throw denied();
    }

    @Override
    public Mono<ReproofingAuthorization> consume(UUID id, ReproofingAuthorizationRequest request) {
        return Mono.defer(() -> {
            validate(request.binding());
            return nativeOwner
                    .admit(
                            new ReproofingNativeRequest(
                                    request.binding(), id, request.executionId()))
                    .switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(nativeAdmission ->
                            transaction(() ->
                                    load(id, request.binding(), true)
                                            .flatMap(state ->
                                                    now().flatMap(databaseNow -> {
                                                        admission(nativeAdmission, request.binding(), databaseNow, true);
                                                        if (nativeAdmission.expiresAt().isAfter(state.expiry()))
                                                            throw denied();
                                                        authorization(state, request, databaseNow, false);
                                                        if ("CONSUMED".equals(state.status()))
                                                            return Mono.just(state.grant());
                                                        return database.sql(
                                                                        "UPDATE"
                                                                            + " identity_reproofing_case"
                                                                            + " SET status='CONSUMED'"
                                                                            + " WHERE case_id=:id")
                                                                .bind("id", id)
                                                                .fetch()
                                                                .rowsUpdated()
                                                                .then(audit(id, "CONSUMED"))
                                                                .thenReturn(state.grant());
                                                    }))));
        });
    }

    @Override
    public Mono<ReproofingAuthorization> verify(UUID id, ReproofingAuthorizationRequest request) {
        return load(id, request.binding(), false)
                .flatMap(state ->
                        now().map(databaseNow -> {
                            authorization(state, request, databaseNow, true);
                            return state.grant();
                        }));
    }

    @Override
    public Mono<ReproofingCaseResponse> record(UUID id, RecordReproofingExecutionRequest request) {
        return Mono.defer(() -> {
            validate(request.binding());
            if (!"CREDENTIALS_REPLACED".equals(request.outcome())) throw denied();
            return nativeOwner
                    .journal(
                            new ReproofingNativeRequest(
                                    request.binding(), id, request.executionId()))
                    .switchIfEmpty(Mono.error(denied()))
                    .flatMap(j ->
                            transaction(() ->
                                    load(id, request.binding(), true)
                                            .flatMap(state ->
                                                    now().flatMap(databaseNow -> {
                                                        if (!java.util.Set.of(
                                                                                "CONSUMED",
                                                                                "EXECUTED")
                                                                        .contains(state.status())
                                                                || !Objects.equals(
                                                                        state.authorization(),
                                                                        request.authorizationId())
                                                                || !Objects.equals(
                                                                        state.execution(),
                                                                        request.executionId())
                                                                || !id.equals(j.caseId())
                                                                || !state.execution()
                                                                        .equals(j.executionId())
                                                                || !state.binding().equals(j.binding())
                                                                || !request.outcome()
                                                                        .equals(j.outcome())
                                                                || j.committedAt() == null
                                                                || j.committedAt().isBefore(state.grantIssuedAt())
                                                                || j.committedAt().isAfter(databaseNow)
                                                                || j.committedAt()
                                                                        .isAfter(state.grantExpiry()))
                                                            throw denied();
                                                        return database.sql(
                                                                        "UPDATE"
                                                                            + " identity_reproofing_case"
                                                                            + " SET status='EXECUTED'"
                                                                            + " WHERE case_id=:id")
                                                                .bind("id", id)
                                                                .fetch()
                                                                .rowsUpdated()
                                                                .then(audit(id, "EXECUTED"))
                                                                .then(
                                                                        load(
                                                                                id,
                                                                                request.binding(),
                                                                                false))
                                                                .map(value -> value.response(databaseNow));
                                                    }))));
        });
    }

    private final class State {
        private final Map<String, Object> row;

        State(Map<String, Object> row) {
            this.row = row;
        }

        String str(String k) {
            return (String) row.get(k);
        }

        UUID uuid(String k) {
            return (UUID) row.get(k);
        }

        Instant instant(String k) {
            var value = row.get(k);
            return value == null ? null : ((OffsetDateTime) value).toInstant();
        }

        UUID id() {
            return uuid("case_id");
        }

        UUID authorization() {
            return uuid("authorization_id");
        }

        UUID execution() {
            return uuid("execution_id");
        }

        UUID confirmation() {
            return uuid("confirmation_id");
        }

        Instant expiry() {
            return instant("expires_at");
        }

        Instant grantIssuedAt() {
            return instant("grant_issued_at");
        }

        Instant grantExpiry() {
            return instant("grant_expires_at");
        }

        Instant reservedUntil() {
            return instant("reserved_until");
        }

        String status() {
            return str("status");
        }

        String provider() {
            return str("provider_id");
        }

        String policy() {
            return str("policy_id");
        }

        String session() {
            return str("provider_session_id");
        }

        String budgetKey() {
            return str("subject_budget_key");
        }

        int failures() {
            return (Integer) row.get("failures");
        }

        ReproofingBinding binding() {
            return decode(str("binding"));
        }

        ReproofingAuthorization grant() {
            return authorization() == null
                    ? null
                    : new ReproofingAuthorization(
                            id(),
                            authorization(),
                            execution(),
                            binding(),
                            str("evidence_digest"),
                            policy(),
                            grantExpiry());
        }

        ReproofingCaseResponse response(Instant databaseNow) {
            boolean expired =
                    !"EXECUTED".equals(status())
                            && (!databaseNow.isBefore(expiry())
                                    || grantExpiry() != null && !databaseNow.isBefore(grantExpiry()));
            return new ReproofingCaseResponse(
                    id(),
                    uuid("provider_start_id"),
                    binding(),
                    expired ? "EXPIRED" : status(),
                    provider(),
                    policy(),
                    session(),
                    expiry(),
                    expired || grantExpiry() != null && !databaseNow.isBefore(grantExpiry())
                            ? null
                            : grant());
        }
    }
}
