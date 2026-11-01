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
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final ReproofingNativePort nativeOwner;
    private final ReproofingEvidencePort evidenceOwner;
    private final String provider, policy, issuer, realm, tenant;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReproofingServiceImpl(DatabaseClient db, TransactionalOperator tx, ReproofingNativePort nativeOwner,
            ReproofingEvidencePort evidenceOwner, String provider, String policy, String issuer, String realm, String tenant) {
        this.db=db; this.tx=tx; this.nativeOwner=nativeOwner; this.evidenceOwner=evidenceOwner;
        this.provider=text(provider,512); this.policy=text(policy,512); this.issuer=text(issuer,512);
        this.realm=text(realm,512); this.tenant=text(tenant,512);
        URI uri=URI.create(issuer);
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("HTTPS issuer required");
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Recovery refused");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Recovery unavailable");}
    private static String text(String value,int max){
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(Character::isISOControl)) throw denied();
        return value;
    }
    private void validate(ReproofingBinding b){
        if(b==null || b.partyId()==null || !issuer.equals(b.issuer()) || !realm.equals(b.realm()) || !tenant.equals(b.tenant())
                || !"HUMAN".equals(b.population()) || !"CREDENTIAL_REPLACEMENT".equals(b.purpose())) throw denied();
        text(b.subject(),512);text(b.generation(),512);text(b.credentialRevision(),512);text(b.nonce(),128);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw unavailable();}}
    private ReproofingBinding decode(String value){try{return json.readValue(value,ReproofingBinding.class);}catch(Exception error){throw unavailable();}}
    private String digest(String... values){
        try {var md=MessageDigest.getInstance("SHA-256");for(String v:values){byte[] bytes=v.getBytes(StandardCharsets.UTF_8);md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());md.update(bytes);}return HexFormat.of().formatHex(md.digest());}
        catch(Exception error){throw unavailable();}
    }
    private <T> Mono<T> transaction(Supplier<Mono<T>> work){return tx.transactional(Mono.defer(work));}
    private Mono<Instant> now(){return db.sql("SELECT clock_timestamp() AS now").map((r,m)->r.get("now",OffsetDateTime.class).toInstant()).one();}
    private Mono<State> load(UUID id,ReproofingBinding binding,boolean lock){
        validate(binding);if(id==null)return Mono.error(denied());
        return db.sql("SELECT * FROM identity_reproofing_case WHERE case_id=:id"+(lock?" FOR UPDATE":"")).bind("id",id)
                .fetch().one().switchIfEmpty(Mono.error(denied())).map(row->{State s=new State(row);if(!s.binding().equals(binding))throw denied();return s;});
    }
    private Mono<Void> audit(UUID id,String event){return db.sql("INSERT INTO identity_reproofing_audit(case_id,event) VALUES(:id,:event) ON CONFLICT DO NOTHING")
            .bind("id",id).bind("event",event).fetch().rowsUpdated().then();}
    private void live(State s,Instant time){if(!time.isBefore(s.expiry()) || "DENIED".equals(s.status()))throw denied();}
    private void admission(ReproofingAdmission a,ReproofingBinding b,Instant time,boolean eligible){
        if(a==null || !b.equals(a.binding()) || a.expiresAt()==null || !time.isBefore(a.expiresAt()) || eligible&&!a.eligible())throw denied();
    }
    @Override public Mono<ReproofingCaseResponse> create(CreateReproofingCaseRequest request){return Mono.defer(()->{
        ReproofingBinding b=request.binding();validate(b);
        return nativeOwner.prepare(new ReproofingNativeRequest(b,null,null)).switchIfEmpty(Mono.error(unavailable())).flatMap(a->transaction(()->now().flatMap(time->{
            admission(a,b,time,false);
            UUID id=UUID.randomUUID();Instant expiry=a.expiresAt().isBefore(time.plusSeconds(600))?a.expiresAt():time.plusSeconds(600);
            String key=digest(b.issuer(),b.nonce());
            return db.sql("INSERT INTO identity_reproofing_case(case_id,provider_start_id,binding,issuer_nonce_key,subject_budget_key,status,provider_id,policy_id,expires_at) VALUES(:id,:start,:binding,:key,:budget,'PENDING',:provider,:policy,:expiry) ON CONFLICT(issuer_nonce_key) DO NOTHING")
                .bind("id",id).bind("start",UUID.randomUUID()).bind("binding",encode(b)).bind("key",key).bind("budget",digest(b.issuer(),b.subject(),b.generation()))
                .bind("provider",provider).bind("policy",policy).bind("expiry",OffsetDateTime.ofInstant(expiry,java.time.ZoneOffset.UTC)).fetch().rowsUpdated()
                .then(db.sql("SELECT * FROM identity_reproofing_case WHERE issuer_nonce_key=:key FOR UPDATE").bind("key",key).fetch().one())
                .map(State::new).flatMap(s->{if(!s.binding().equals(b))throw denied();live(s,time);return audit(s.id(),"CREATED").thenReturn(s.response(time));});
        })));
    });}
    @Override public Mono<ReproofingCaseResponse> inspect(UUID id,ConfirmReproofingRequest request){return load(id,request.binding(),false).flatMap(s->now().map(s::response));}
    @Override public Mono<ReproofingCaseResponse> bindSession(UUID id,BindReproofingSessionRequest request){return transaction(()->load(id,request.binding(),true).flatMap(s->now().flatMap(time->{
        live(s,time);text(request.providerSessionId(),512);
        if(s.session()!=null){if(!s.session().equals(request.providerSessionId()))throw denied();return Mono.just(s.response(time));}
        if(!"PENDING".equals(s.status()))throw denied();
        return db.sql("UPDATE identity_reproofing_case SET provider_session_id=:session WHERE case_id=:id").bind("id",id).bind("session",request.providerSessionId()).fetch().rowsUpdated()
            .then(audit(id,"SESSION_BOUND")).then(load(id,request.binding(),false)).map(v->v.response(time));
    })));}
    @Override public Mono<ReproofingCaseResponse> confirm(UUID id,ConfirmReproofingRequest request){return Mono.defer(()->{
        UUID reservation=UUID.randomUUID();
        return transaction(()->load(id,request.binding(),true).flatMap(s->now().flatMap(time->{
            live(s,time);if(s.authorization()!=null){if(!time.isBefore(s.grantExpiry()))throw denied();return Mono.just(s);}
            if(s.session()==null || !"PENDING".equals(s.status()) || s.failures()>=3)throw denied();
            if(s.reservedUntil()!=null && time.isBefore(s.reservedUntil()))throw unavailable();
            return db.sql("UPDATE identity_reproofing_case SET confirmation_id=:token,reserved_until=clock_timestamp()+interval '15 seconds' WHERE case_id=:id")
                .bind("token",reservation).bind("id",id).fetch().rowsUpdated().then(load(id,request.binding(),false));
        }))).flatMap(s->{
            if(s.authorization()!=null)return now().map(s::response);
            var evidenceRequest=new ReproofingEvidenceRequest(s.id(),s.binding(),s.provider(),s.policy(),s.session());
            return evidenceOwner.verify(evidenceRequest).switchIfEmpty(Mono.error(unavailable())).flatMap(evidence->
                nativeOwner.admit(new ReproofingNativeRequest(s.binding(),s.id(),null)).switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(a->finishConfirmation(id,request.binding(),reservation,evidence,a)))
                .onErrorResume(error->release(id,reservation).then(Mono.error(error instanceof ResponseStatusException?error:unavailable())));
        });
    });}
    private Mono<Void> release(UUID id,UUID reservation){return db.sql("UPDATE identity_reproofing_case SET confirmation_id=NULL,reserved_until=NULL WHERE case_id=:id AND confirmation_id=:token")
            .bind("id",id).bind("token",reservation).fetch().rowsUpdated().then();}
    private boolean envelope(State s,VerifiedReproofingEvidence e,Instant time){
        return e!=null && s.id().equals(e.caseId()) && s.binding().equals(e.binding()) && s.provider().equals(e.providerId())
            && s.policy().equals(e.policyId()) && s.session().equals(e.providerSessionId())
            && e.verifiedAt()!=null && !e.verifiedAt().isAfter(time) && e.verifiedAt().isAfter(time.minusSeconds(600))
            && e.expiresAt()!=null && time.isBefore(e.expiresAt()) && !e.expiresAt().isAfter(s.expiry())
            && e.evidenceId()!=null && !e.evidenceId().isBlank() && e.evidenceId().length()<=512
            && e.evidenceDigest()!=null && !e.evidenceDigest().isBlank() && e.evidenceDigest().length()<=512;
    }
    private Mono<ReproofingCaseResponse> finishConfirmation(UUID id,ReproofingBinding binding,UUID reservation,VerifiedReproofingEvidence evidence,ReproofingAdmission a){
        return transaction(()->load(id,binding,true).flatMap(s->now().flatMap(time->{
            live(s,time);admission(a,binding,time,true);
            if(!reservation.equals(s.confirmation()) || s.reservedUntil()==null || !time.isBefore(s.reservedUntil()))throw denied();
            boolean matched=envelope(s,evidence,time) && evidence.referenceMatched() && binding.partyId().equals(evidence.referenceId());
            boolean passed=matched && evidence.documentAuthentic() && evidence.liveMatch() && evidence.fraudCleared();
            Mono<Integer> budget=matched?db.sql("INSERT INTO identity_reproofing_budget(subject_budget_key,failures) VALUES(:key,0) ON CONFLICT DO NOTHING")
                .bind("key",s.budgetKey()).fetch().rowsUpdated().then(db.sql("SELECT failures FROM identity_reproofing_budget WHERE subject_budget_key=:key FOR UPDATE")
                .bind("key",s.budgetKey()).map((r,m)->r.get("failures",Integer.class)).one()):Mono.just(0);
            return budget.flatMap(failures->{
                if(passed && failures<3){
                    Instant expiry=time.plusSeconds(120);if(expiry.isAfter(s.expiry()))expiry=s.expiry();if(expiry.isAfter(evidence.expiresAt()))expiry=evidence.expiresAt();
                    return db.sql("UPDATE identity_reproofing_case SET status='VERIFIED',authorization_id=:authorization,execution_id=:execution,evidence_id=:evidence,evidence_digest=:digest,grant_expires_at=:expiry,confirmation_id=NULL,reserved_until=NULL WHERE case_id=:id")
                        .bind("id",id).bind("authorization",UUID.randomUUID()).bind("execution",UUID.randomUUID()).bind("evidence",evidence.evidenceId()).bind("digest",evidence.evidenceDigest())
                        .bind("expiry",OffsetDateTime.ofInstant(expiry,java.time.ZoneOffset.UTC)).fetch().rowsUpdated().then(audit(id,"VERIFIED"))
                        .then(load(id,binding,false)).map(v->v.response(time));
                }
                Mono<Void> charge=matched && failures<3?db.sql("UPDATE identity_reproofing_budget SET failures=failures+1 WHERE subject_budget_key=:key")
                    .bind("key",s.budgetKey()).fetch().rowsUpdated().then():Mono.empty();
                return charge.then(db.sql("UPDATE identity_reproofing_case SET failures=failures+1,status=CASE WHEN failures>=2 OR :exhausted THEN 'DENIED' ELSE 'PENDING' END,confirmation_id=NULL,reserved_until=NULL WHERE case_id=:id")
                    .bind("exhausted",failures>=3).bind("id",id).fetch().rowsUpdated()).then(audit(id,"CONFIRMATION_FAILED_"+reservation))
                    .then(load(id,binding,false)).map(v->v.response(time));
            });
        })));
    }
    private void authorization(State s,ReproofingAuthorizationRequest r,Instant time,boolean consumed){
        live(s,time);
        if(s.authorization()==null || !s.authorization().equals(r.authorizationId()) || !s.execution().equals(r.executionId())
                || !time.isBefore(s.grantExpiry()) || (consumed?!"CONSUMED".equals(s.status()):!java.util.Set.of("VERIFIED","CONSUMED").contains(s.status())))throw denied();
    }
    @Override public Mono<ReproofingAuthorization> consume(UUID id,ReproofingAuthorizationRequest request){return Mono.defer(()->{
        validate(request.binding());
        return nativeOwner.admit(new ReproofingNativeRequest(request.binding(),id,request.executionId())).switchIfEmpty(Mono.error(unavailable()))
            .flatMap(a->transaction(()->load(id,request.binding(),true).flatMap(s->now().flatMap(time->{
                admission(a,request.binding(),time,true);authorization(s,request,time,false);
                if("CONSUMED".equals(s.status()))return Mono.just(s.grant());
                return db.sql("UPDATE identity_reproofing_case SET status='CONSUMED' WHERE case_id=:id").bind("id",id).fetch().rowsUpdated()
                    .then(audit(id,"CONSUMED")).thenReturn(s.grant());
            }))));
    });}
    @Override public Mono<ReproofingAuthorization> verify(UUID id,ReproofingAuthorizationRequest request){return load(id,request.binding(),false).flatMap(s->now().map(time->{authorization(s,request,time,true);return s.grant();}));}
    @Override public Mono<ReproofingCaseResponse> record(UUID id,RecordReproofingExecutionRequest request){return Mono.defer(()->{
        validate(request.binding());if(!"CREDENTIALS_REPLACED".equals(request.outcome()))throw denied();
        return nativeOwner.journal(new ReproofingNativeRequest(request.binding(),id,request.executionId())).switchIfEmpty(Mono.error(denied()))
            .flatMap(j->transaction(()->load(id,request.binding(),true).flatMap(s->now().flatMap(time->{
                if(!java.util.Set.of("CONSUMED","EXECUTED").contains(s.status()) || !Objects.equals(s.authorization(),request.authorizationId()) || !Objects.equals(s.execution(),request.executionId())
                    || !id.equals(j.caseId()) || !s.execution().equals(j.executionId()) || !s.binding().equals(j.binding()) || !request.outcome().equals(j.outcome())
                    || j.committedAt()==null || j.committedAt().isAfter(time) || j.committedAt().isAfter(s.grantExpiry()))throw denied();
                return db.sql("UPDATE identity_reproofing_case SET status='EXECUTED' WHERE case_id=:id").bind("id",id).fetch().rowsUpdated()
                    .then(audit(id,"EXECUTED")).then(load(id,request.binding(),false)).map(v->v.response(time));
            }))));
    });}
    private final class State {
        private final Map<String,Object> row;
        State(Map<String,Object> row){this.row=row;}
        String str(String k){return (String)row.get(k);}
        UUID uuid(String k){return (UUID)row.get(k);}
        Instant instant(String k){var v=row.get(k);return v==null?null:((OffsetDateTime)v).toInstant();}
        UUID id(){return uuid("case_id");} UUID authorization(){return uuid("authorization_id");} UUID execution(){return uuid("execution_id");} UUID confirmation(){return uuid("confirmation_id");}
        Instant expiry(){return instant("expires_at");} Instant grantExpiry(){return instant("grant_expires_at");} Instant reservedUntil(){return instant("reserved_until");}
        String status(){return str("status");} String provider(){return str("provider_id");} String policy(){return str("policy_id");} String session(){return str("provider_session_id");}
        String budgetKey(){return str("subject_budget_key");} int failures(){return (Integer)row.get("failures");} ReproofingBinding binding(){return decode(str("binding"));}
        ReproofingAuthorization grant(){return authorization()==null?null:new ReproofingAuthorization(id(),authorization(),execution(),binding(),str("evidence_digest"),policy(),grantExpiry());}
        ReproofingCaseResponse response(Instant time){
            boolean expired=!"EXECUTED".equals(status()) && (!time.isBefore(expiry()) || grantExpiry()!=null&&!time.isBefore(grantExpiry()));
            return new ReproofingCaseResponse(id(),uuid("provider_start_id"),binding(),expired?"EXPIRED":status(),provider(),policy(),session(),expiry(),expired?null:grant());
        }
    }
}
