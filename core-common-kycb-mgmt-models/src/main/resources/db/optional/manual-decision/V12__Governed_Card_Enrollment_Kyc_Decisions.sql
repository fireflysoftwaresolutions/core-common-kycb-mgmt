-- Explicit deployment after the original immutable-audit postgresql.sql and completed-append.sql.
-- Legacy case/document rows remain evidence sources; their writable VERIFIED flags confer no admission.
BEGIN;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='5s';
DO $$ BEGIN
    IF NOT EXISTS(SELECT FROM pg_catalog.pg_roles WHERE rolname='firefly_audit_writer' AND rolcanlogin
        AND NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication)
        OR NOT EXISTS(SELECT FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_roles r ON r.oid=p.proowner
            WHERE p.oid=to_regprocedure('public.firefly_audit_require_completed(uuid,uuid,bigint,text,boolean)')
                AND p.prosecdef AND p.provolatile='v' AND p.proconfig=ARRAY['search_path=pg_catalog'] AND r.rolname='firefly_audit_guard') THEN
        RAISE EXCEPTION 'Original restricted completed audit deployment required' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='kyc_manual_guard') THEN
        CREATE ROLE kyc_manual_guard NOLOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='kyc_manual_reader') THEN
        CREATE ROLE kyc_manual_reader NOLOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname IN('kyc_manual_guard','kyc_manual_reader') AND (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication))
        OR pg_has_role('firefly_audit_writer','kyc_manual_guard','MEMBER') OR pg_has_role('kyc_manual_reader','kyc_manual_guard','MEMBER') THEN
        RAISE EXCEPTION 'Restricted KYC role graph required' USING ERRCODE='42501';
    END IF;
END $$;
CREATE SCHEMA kyc_manual_private AUTHORIZATION kyc_manual_guard;
REVOKE ALL ON SCHEMA kyc_manual_private FROM PUBLIC;
CREATE TABLE public.kyc_manual_action (
    action_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,
    kyc_case_id UUID NOT NULL REFERENCES public.kyc_verification(kyc_verification_id),receipt_id UUID NOT NULL UNIQUE,event_id UUID NOT NULL UNIQUE,
    operation TEXT NOT NULL CHECK(operation IN('SUBMIT','REVIEW','REVOKE')),selection_id UUID NOT NULL,
    intent_digest TEXT NOT NULL CHECK(intent_digest COLLATE "C" ~ '^[0-9a-f]{64}$'),
    payload TEXT NOT NULL CHECK(octet_length(payload)<=262144),payload_digest TEXT NOT NULL CHECK(payload_digest=encode(sha256(convert_to(payload,'UTF8')),'hex')),
    resulting_state TEXT NOT NULL CHECK(resulting_state IN('SUBMITTED','APPROVED','REJECTED','REVOKED')),
    resulting_version BIGINT NOT NULL CHECK(resulting_version>=0),recorded_at TIMESTAMPTZ NOT NULL,
    actor_issuer TEXT NOT NULL,actor_subject TEXT NOT NULL,actor_client_id TEXT NOT NULL,platform_tenant_id TEXT NOT NULL,
    staff_realm TEXT NOT NULL,staff_actor_id UUID NOT NULL,policy_revision TEXT NOT NULL,
    UNIQUE(tenant_id,party_id,kyc_case_id,action_id),UNIQUE(tenant_id,kyc_case_id,action_id),
    CHECK('00000000-0000-0000-0000-000000000000'::uuid<>ALL(ARRAY[action_id,tenant_id,party_id,kyc_case_id,receipt_id,event_id,selection_id,staff_actor_id])),
    CHECK(NULLIF(actor_issuer,'') IS NOT NULL AND NULLIF(actor_subject,'') IS NOT NULL AND NULLIF(actor_client_id,'') IS NOT NULL
        AND platform_tenant_id=tenant_id::text AND NULLIF(staff_realm,'') IS NOT NULL AND NULLIF(policy_revision,'') IS NOT NULL)
);
CREATE TABLE public.kyc_manual_control (
    kyc_case_id UUID PRIMARY KEY REFERENCES public.kyc_verification(kyc_verification_id),tenant_id UUID NOT NULL,party_id UUID NOT NULL,
    submission_action_id UUID NOT NULL,review_action_id UUID,state TEXT NOT NULL CHECK(state IN('SUBMITTED','APPROVED','REJECTED','REVOKED')),
    version BIGINT NOT NULL CHECK(version>=0),UNIQUE(tenant_id,party_id,kyc_case_id),
    FOREIGN KEY(tenant_id,party_id,kyc_case_id,submission_action_id) REFERENCES public.kyc_manual_action(tenant_id,party_id,kyc_case_id,action_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,party_id,kyc_case_id,review_action_id) REFERENCES public.kyc_manual_action(tenant_id,party_id,kyc_case_id,action_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK(state NOT IN('APPROVED','REJECTED') OR review_action_id IS NOT NULL)
);
CREATE TABLE public.kyc_manual_evidence (
    binding_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,party_id UUID NOT NULL,kyc_case_id UUID NOT NULL,submission_action_id UUID NOT NULL,
    document_id UUID NOT NULL REFERENCES public.verification_document(verification_document_id),identity_document_id UUID,
    document_type TEXT NOT NULL,verification_purpose TEXT,document_reference TEXT NOT NULL,document_system_id TEXT NOT NULL,
    source_expiry_date TIMESTAMP,source_expiry_zone TEXT,source_expiry_offset_seconds INTEGER,source_expires_at TIMESTAMPTZ,
    CHECK((source_expiry_date IS NULL AND source_expiry_zone IS NULL AND source_expiry_offset_seconds IS NULL AND source_expires_at IS NULL)
        OR (source_expiry_date IS NOT NULL AND source_expiry_zone IS NOT NULL AND source_expiry_offset_seconds IS NOT NULL AND source_expiry_offset_seconds BETWEEN -64800 AND 64800 AND source_expires_at IS NOT NULL)),
    content_digest TEXT NOT NULL CHECK(content_digest COLLATE "C" ~ '^[0-9a-f]{64}$'),
    collected_at TIMESTAMPTZ NOT NULL,valid_until TIMESTAMPTZ NOT NULL CHECK(valid_until>collected_at),
    UNIQUE(submission_action_id,document_id),
    FOREIGN KEY(tenant_id,party_id,kyc_case_id,submission_action_id) REFERENCES public.kyc_manual_action(tenant_id,party_id,kyc_case_id,action_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.kyc_manual_audit (
    action_id UUID PRIMARY KEY,tenant_id UUID NOT NULL,kyc_case_id UUID NOT NULL,event_id UUID NOT NULL,audit_context_nonce UUID NOT NULL,
    audit_sequence BIGINT NOT NULL CHECK(audit_sequence>0),audit_record_hash TEXT NOT NULL CHECK(audit_record_hash COLLATE "C" ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY(tenant_id,kyc_case_id,action_id) REFERENCES public.kyc_manual_action(tenant_id,kyc_case_id,action_id),
    FOREIGN KEY(tenant_id,event_id) REFERENCES firefly_audit.audit_event(resource_tenant_id,event_id)
);
CREATE TABLE kyc_manual_private.context (
    xid XID8 PRIMARY KEY,backend_pid INTEGER NOT NULL,login_name NAME NOT NULL,nonce UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,party_id UUID NOT NULL,kyc_case_id UUID NOT NULL,action_id UUID NOT NULL,selection_id UUID NOT NULL,
    purpose TEXT NOT NULL,intent_digest TEXT,expected_version BIGINT,issuer TEXT NOT NULL,subject TEXT NOT NULL,client_id TEXT NOT NULL,
    platform_tenant_id TEXT NOT NULL,staff_realm TEXT,staff_actor_id UUID,policy_revision TEXT NOT NULL,source_expiry_zone TEXT NOT NULL,
    authority_until TIMESTAMPTZ NOT NULL,command_until TIMESTAMPTZ NOT NULL,database_time TIMESTAMPTZ NOT NULL,
    original_action_existed BOOLEAN NOT NULL,original_binding_existed BOOLEAN NOT NULL,original_control_existed BOOLEAN NOT NULL,
    original_state TEXT,original_version BIGINT,audit_paired BOOLEAN NOT NULL DEFAULT false
);
ALTER TABLE kyc_manual_private.context OWNER TO kyc_manual_guard;
REVOKE ALL ON kyc_manual_private.context FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO kyc_manual_guard,kyc_manual_reader,firefly_audit_writer;
GRANT CREATE ON SCHEMA public TO kyc_manual_guard;
GRANT SELECT ON public.kyc_verification,public.verification_document TO kyc_manual_guard;
GRANT UPDATE(kyc_verification_id) ON public.kyc_verification TO kyc_manual_guard;
GRANT UPDATE(verification_document_id) ON public.verification_document TO kyc_manual_guard;
GRANT EXECUTE ON FUNCTION public.firefly_audit_require_completed(UUID,UUID,BIGINT,TEXT,BOOLEAN) TO kyc_manual_guard;

CREATE FUNCTION public.kyc_manual_matches(p_tenant UUID,p_party UUID,p_case UUID) RETURNS BOOLEAN
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM kyc_manual_private.context c WHERE c.xid=pg_current_xact_id_if_assigned() AND c.backend_pid=pg_backend_pid()
    AND c.login_name=session_user AND (c.tenant_id,c.party_id,c.kyc_case_id)=(p_tenant,p_party,p_case)
    AND c.authority_until>clock_timestamp() AND c.command_until>clock_timestamp())
$$;
ALTER FUNCTION public.kyc_manual_matches(UUID,UUID,UUID) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION public.kyc_manual_matches(UUID,UUID,UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.kyc_manual_matches(UUID,UUID,UUID) TO firefly_audit_writer,kyc_manual_reader;

-- The mapped source views expose only the exact guarded case and its selected evidence.
CREATE VIEW public.kyc_manual_case_source WITH (security_barrier=true) AS
 SELECT v.kyc_verification_id,v.party_id FROM public.kyc_verification v
 JOIN kyc_manual_private.context c ON c.kyc_case_id=v.kyc_verification_id AND c.party_id=v.party_id
 WHERE c.xid=pg_current_xact_id_if_assigned() AND c.backend_pid=pg_backend_pid() AND c.login_name=session_user
    AND c.authority_until>clock_timestamp() AND c.command_until>clock_timestamp();
CREATE VIEW public.kyc_manual_document_source WITH (security_barrier=true) AS
 SELECT d.verification_document_id,d.kyc_verification_id,d.identity_document_id,d.document_type,d.verification_purpose,
    d.document_reference,d.document_system_id,d.expiry_date FROM public.verification_document d
 JOIN kyc_manual_private.context c ON c.kyc_case_id=d.kyc_verification_id
 WHERE c.xid=pg_current_xact_id_if_assigned() AND c.backend_pid=pg_backend_pid() AND c.login_name=session_user
    AND c.authority_until>clock_timestamp() AND c.command_until>clock_timestamp();
GRANT CREATE ON SCHEMA public TO kyc_manual_guard;
ALTER VIEW public.kyc_manual_case_source OWNER TO kyc_manual_guard;
ALTER VIEW public.kyc_manual_document_source OWNER TO kyc_manual_guard;
REVOKE ALL ON public.kyc_manual_case_source,public.kyc_manual_document_source FROM PUBLIC;
GRANT SELECT ON public.kyc_manual_case_source,public.kyc_manual_document_source TO firefly_audit_writer,kyc_manual_reader;
CREATE FUNCTION public.kyc_manual_case_matches(p_tenant UUID,p_case UUID) RETURNS BOOLEAN
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT FROM kyc_manual_private.context c WHERE c.xid=pg_current_xact_id_if_assigned() AND c.backend_pid=pg_backend_pid()
    AND c.login_name=session_user AND (c.tenant_id,c.kyc_case_id)=(p_tenant,p_case)
    AND c.authority_until>clock_timestamp() AND c.command_until>clock_timestamp())
$$;
ALTER FUNCTION public.kyc_manual_case_matches(UUID,UUID) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION public.kyc_manual_case_matches(UUID,UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.kyc_manual_case_matches(UUID,UUID) TO firefly_audit_writer,kyc_manual_reader;

CREATE FUNCTION public.kyc_manual_enter(p_nonce UUID,p_tenant UUID,p_party UUID,p_case UUID,p_action UUID,p_selection UUID,
    p_purpose TEXT,p_intent TEXT,p_version BIGINT,p_documents UUID[],p_issuer TEXT,p_subject TEXT,p_client TEXT,p_platform TEXT,
    p_realm TEXT,p_staff UUID,p_policy TEXT,p_source_expiry_zone TEXT,p_authority_until TIMESTAMPTZ,p_command_until TIMESTAMPTZ)
RETURNS TABLE(context_nonce UUID,database_time TIMESTAMPTZ,command_until TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE original public.kyc_manual_control%ROWTYPE;at_time TIMESTAMPTZ;until_time TIMESTAMPTZ;selected UUID[];locked_count INTEGER;
BEGIN
    IF p_purpose IS NULL OR p_purpose NOT IN('SUBMIT','REVIEW','REVOKE','CURRENT','RECOVER')
        OR (p_purpose NOT IN('CURRENT','RECOVER') AND session_user<>'firefly_audit_writer')
        OR (p_purpose IN('CURRENT','RECOVER') AND NOT pg_has_role(session_user,'kyc_manual_reader','MEMBER'))
        OR EXISTS(SELECT FROM pg_roles WHERE rolname=session_user AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication))
        OR pg_has_role(session_user,'kyc_manual_guard','MEMBER')
        OR p_nonce IS NULL OR p_tenant IS NULL OR p_party IS NULL OR p_case IS NULL OR p_action IS NULL OR p_selection IS NULL
        OR '00000000-0000-0000-0000-000000000000'::uuid=ANY(ARRAY[p_nonce,p_tenant,p_party,p_case,p_action,p_selection])
        OR NULLIF(p_issuer,'') IS NULL OR NULLIF(p_subject,'') IS NULL OR NULLIF(p_client,'') IS NULL OR p_platform IS DISTINCT FROM p_tenant::text
        OR NULLIF(p_policy,'') IS NULL OR NOT EXISTS(SELECT FROM pg_catalog.pg_timezone_names WHERE name=p_source_expiry_zone) OR p_authority_until IS NULL OR p_command_until IS NULL
        OR (p_purpose<>'CURRENT' AND (NULLIF(p_realm,'') IS NULL OR p_staff IS NULL OR p_staff='00000000-0000-0000-0000-000000000000'::uuid))
        OR (p_purpose NOT IN('CURRENT','RECOVER') AND (p_intent IS NULL OR p_intent COLLATE "C" !~ '^[0-9a-f]{64}$' OR p_version IS NULL OR p_version< -1)) THEN
        RAISE EXCEPTION 'Current restricted KYC manual context required' USING ERRCODE='42501';
    END IF;
    -- Shared existing case lock precedes control and document locks for all governed commands.
    PERFORM 1 FROM public.kyc_verification WHERE kyc_verification_id=p_case AND party_id=p_party FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact existing KYC party case required' USING ERRCODE='42501';END IF;
    SELECT * INTO original FROM public.kyc_manual_control WHERE kyc_case_id=p_case;
    IF FOUND AND (original.tenant_id,original.party_id) IS DISTINCT FROM (p_tenant,p_party) THEN
        RAISE EXCEPTION 'Original KYC tenant ownership required' USING ERRCODE='42501';
    END IF;
    IF p_purpose='SUBMIT' THEN
        IF p_documents IS NULL OR cardinality(p_documents)<1 OR cardinality(p_documents)>32
            OR cardinality(p_documents)<>(SELECT count(DISTINCT d) FROM unnest(p_documents) d) THEN
            RAISE EXCEPTION 'Exact bounded KYC document selection required' USING ERRCODE='23514';
        END IF;
        selected:=p_documents;
    ELSE
        SELECT array_agg(e.document_id ORDER BY e.document_id) INTO selected FROM public.kyc_manual_evidence e
            WHERE e.tenant_id=p_tenant AND e.party_id=p_party AND e.kyc_case_id=p_case AND e.submission_action_id=original.submission_action_id;
    END IF;
    IF selected IS NOT NULL THEN
        PERFORM 1 FROM public.verification_document WHERE kyc_verification_id=p_case AND verification_document_id=ANY(selected)
            ORDER BY verification_document_id FOR UPDATE;
        GET DIAGNOSTICS locked_count=ROW_COUNT;
        IF locked_count<>cardinality(selected) THEN RAISE EXCEPTION 'Exact original KYC documents required' USING ERRCODE='23514';END IF;
    END IF;
    at_time:=date_trunc('microseconds',clock_timestamp());until_time:=LEAST(p_authority_until,p_command_until,at_time+interval '4 seconds');
    IF at_time>=until_time THEN RAISE EXCEPTION 'Original KYC authority deadline required' USING ERRCODE='42501';END IF;
    INSERT INTO kyc_manual_private.context(xid,backend_pid,login_name,nonce,tenant_id,party_id,kyc_case_id,action_id,selection_id,purpose,intent_digest,expected_version,
        issuer,subject,client_id,platform_tenant_id,staff_realm,staff_actor_id,policy_revision,source_expiry_zone,authority_until,command_until,database_time,
        original_action_existed,original_binding_existed,original_control_existed,original_state,original_version)
    VALUES(pg_current_xact_id(),pg_backend_pid(),session_user,p_nonce,p_tenant,p_party,p_case,p_action,p_selection,p_purpose,p_intent,p_version,
        p_issuer,p_subject,p_client,p_platform,p_realm,p_staff,p_policy,p_source_expiry_zone,p_authority_until,until_time,at_time,
        EXISTS(SELECT FROM public.kyc_manual_action WHERE action_id=p_action),EXISTS(SELECT FROM public.kyc_manual_audit WHERE action_id=p_action),
        original.kyc_case_id IS NOT NULL,original.state,original.version);
    RETURN QUERY SELECT p_nonce,at_time,until_time;
END $$;
ALTER FUNCTION public.kyc_manual_enter(UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,UUID[],TEXT,TEXT,TEXT,TEXT,TEXT,UUID,TEXT,TEXT,TIMESTAMPTZ,TIMESTAMPTZ) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION public.kyc_manual_enter(UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,UUID[],TEXT,TEXT,TEXT,TEXT,TEXT,UUID,TEXT,TEXT,TIMESTAMPTZ,TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.kyc_manual_enter(UUID,UUID,UUID,UUID,UUID,UUID,TEXT,TEXT,BIGINT,UUID[],TEXT,TEXT,TEXT,TEXT,TEXT,UUID,TEXT,TEXT,TIMESTAMPTZ,TIMESTAMPTZ) TO firefly_audit_writer,kyc_manual_reader;

CREATE FUNCTION kyc_manual_private.source_expiry_matches(p_date TIMESTAMP,p_zone TEXT,p_offset INTEGER,p_at TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT CASE WHEN p_date IS NULL THEN p_zone IS NULL AND p_offset IS NULL AND p_at IS NULL
    WHEN p_zone IS NULL OR p_offset IS NULL OR p_at IS NULL OR NOT isfinite(p_date) OR NOT isfinite(p_at)
        OR p_offset NOT BETWEEN -64800 AND 64800 OR NOT EXISTS(SELECT FROM pg_catalog.pg_timezone_names WHERE name=p_zone) THEN false
    ELSE timezone(p_zone,p_at)=p_date AND timezone(p_zone,p_date)=p_at
        AND timezone('UTC',p_date-make_interval(secs=>p_offset))=p_at END
$$;
ALTER FUNCTION kyc_manual_private.source_expiry_matches(TIMESTAMP,TEXT,INTEGER,TIMESTAMPTZ) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION kyc_manual_private.source_expiry_matches(TIMESTAMP,TEXT,INTEGER,TIMESTAMPTZ) FROM PUBLIC;

CREATE FUNCTION kyc_manual_private.guard_write() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c kyc_manual_private.context%ROWTYPE;d public.verification_document%ROWTYPE;body JSONB;
BEGIN
    SELECT * INTO c FROM kyc_manual_private.context WHERE xid=pg_current_xact_id_if_assigned() AND backend_pid=pg_backend_pid() AND login_name=session_user;
    IF NOT FOUND OR session_user<>'firefly_audit_writer' OR c.purpose IN('CURRENT','RECOVER') OR c.original_action_existed OR c.audit_paired
        OR c.authority_until<=clock_timestamp() OR c.command_until<=clock_timestamp() OR TG_OP='DELETE'
        OR (TG_TABLE_NAME<>'kyc_manual_control' AND TG_OP<>'INSERT') THEN
        RAISE EXCEPTION 'Original uncompleted KYC command required' USING ERRCODE='42501';
    END IF;
    IF (NEW.tenant_id,NEW.kyc_case_id) IS DISTINCT FROM (c.tenant_id,c.kyc_case_id) THEN
        RAISE EXCEPTION 'Exact KYC command scope required' USING ERRCODE='42501';
    END IF;
    IF TG_TABLE_NAME='kyc_manual_control' THEN
        IF NEW.party_id<>c.party_id OR NEW.version<>COALESCE(c.original_version+1,0)
            OR c.expected_version<>COALESCE(c.original_version,-1) OR (TG_OP='INSERT')=c.original_control_existed THEN
            RAISE EXCEPTION 'Original KYC control version required' USING ERRCODE='23514';
        END IF;
        IF TG_OP='UPDATE' AND (OLD.kyc_case_id,OLD.tenant_id,OLD.party_id,OLD.version,OLD.state)
            IS DISTINCT FROM (NEW.kyc_case_id,NEW.tenant_id,NEW.party_id,c.original_version,c.original_state) THEN
            RAISE EXCEPTION 'Immutable KYC control owner required' USING ERRCODE='23514';
        END IF;
        IF (c.purpose='SUBMIT' AND (NEW.state<>'SUBMITTED' OR NEW.submission_action_id<>c.action_id OR NEW.review_action_id IS NOT NULL
                OR c.original_control_existed AND c.original_state NOT IN('REJECTED','REVOKED')))
            OR (c.purpose='REVIEW' AND (c.original_state IS DISTINCT FROM 'SUBMITTED' OR NEW.state NOT IN('APPROVED','REJECTED')
                OR NEW.review_action_id IS DISTINCT FROM c.action_id OR NEW.submission_action_id IS DISTINCT FROM c.selection_id))
            OR (c.purpose='REVOKE' AND (c.original_state NOT IN('SUBMITTED','APPROVED') OR NEW.state<>'REVOKED'
                OR NEW.submission_action_id<>OLD.submission_action_id OR NEW.review_action_id IS DISTINCT FROM OLD.review_action_id
                OR c.selection_id IS DISTINCT FROM COALESCE(OLD.review_action_id,OLD.submission_action_id))) THEN
            RAISE EXCEPTION 'Supported original KYC transition required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='kyc_manual_action' THEN
        IF (NEW.action_id,NEW.party_id,NEW.selection_id,NEW.operation,NEW.intent_digest,NEW.recorded_at,NEW.actor_issuer,NEW.actor_subject,
            NEW.actor_client_id,NEW.platform_tenant_id,NEW.staff_realm,NEW.staff_actor_id,NEW.policy_revision)
            IS DISTINCT FROM (c.action_id,c.party_id,c.selection_id,c.purpose,c.intent_digest,c.database_time,c.issuer,c.subject,c.client_id,c.platform_tenant_id,c.staff_realm,c.staff_actor_id,c.policy_revision)
            OR NEW.resulting_version<>COALESCE(c.original_version+1,0) THEN
            RAISE EXCEPTION 'Exact original KYC action and staff required' USING ERRCODE='23514';
        END IF;
        body:=NEW.payload::jsonb;
        IF body->>'actionId' IS DISTINCT FROM c.action_id::text THEN RAISE EXCEPTION 'Original KYC payload action required' USING ERRCODE='23514';END IF;
        IF c.purpose='SUBMIT' AND (body->>'purpose' IS DISTINCT FROM 'CARD_ENROLLMENT' OR body->>'mode' IS DISTINCT FROM 'MANUAL_REVIEW'
            OR body->>'externalExecution' IS DISTINCT FROM 'NOT_REQUESTED' OR body#>>'{scope,tenantId}' IS DISTINCT FROM c.tenant_id::text
            OR body#>>'{scope,partyId}' IS DISTINCT FROM c.party_id::text OR body#>>'{scope,kycCaseId}' IS DISTINCT FROM c.kyc_case_id::text
            OR body#>>'{maker,issuer}' IS DISTINCT FROM c.issuer OR body#>>'{maker,subject}' IS DISTINCT FROM c.subject
            OR body#>>'{maker,staffActorId}' IS DISTINCT FROM c.staff_actor_id::text OR (body->>'submittedAt')::timestamptz IS DISTINCT FROM c.database_time
            OR (body->>'validUntil')::timestamptz<=c.database_time OR NEW.resulting_state<>'SUBMITTED') THEN
            RAISE EXCEPTION 'Exact explicit manual KYC submission required' USING ERRCODE='23514';
        END IF;
        IF c.purpose='REVIEW' AND (body#>>'{reviewer,issuer}' IS DISTINCT FROM c.issuer OR body#>>'{reviewer,subject}' IS DISTINCT FROM c.subject
            OR body#>>'{reviewer,staffActorId}' IS DISTINCT FROM c.staff_actor_id::text OR (body->>'reviewedAt')::timestamptz IS DISTINCT FROM c.database_time
            OR body->>'outcome' NOT IN('APPROVE','REJECT') OR NEW.resulting_state<>CASE body->>'outcome' WHEN 'APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END
            OR NOT EXISTS(SELECT FROM public.kyc_manual_action a WHERE a.action_id=c.selection_id AND a.tenant_id=c.tenant_id AND a.party_id=c.party_id
                AND a.kyc_case_id=c.kyc_case_id AND a.operation='SUBMIT' AND (a.payload::jsonb->>'digest')=body->>'submissionDigest'
                AND (a.payload::jsonb->>'validUntil')::timestamptz>c.database_time AND a.staff_actor_id<>c.staff_actor_id
                AND (a.actor_issuer<>c.issuer OR a.actor_subject<>c.subject))) THEN
            RAISE EXCEPTION 'Independent current manual reviewer required' USING ERRCODE='23514';
        END IF;
        IF c.purpose='REVOKE' AND (NEW.resulting_state<>'REVOKED' OR body->>'selectedActionId' IS DISTINCT FROM c.selection_id::text
            OR (body->>'expectedControlVersion')::bigint IS DISTINCT FROM c.expected_version) THEN
            RAISE EXCEPTION 'Exact selected manual decision revocation required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='kyc_manual_evidence' THEN
        SELECT * INTO d FROM public.verification_document WHERE verification_document_id=NEW.document_id AND kyc_verification_id=c.kyc_case_id;
        IF NOT FOUND OR c.purpose<>'SUBMIT' OR NEW.submission_action_id<>c.action_id OR NEW.party_id<>c.party_id
            OR (NEW.identity_document_id,NEW.document_type,NEW.verification_purpose,NEW.document_reference,NEW.document_system_id,NEW.source_expiry_date)
                IS DISTINCT FROM (d.identity_document_id,d.document_type::text,d.verification_purpose::text,d.document_reference,d.document_system_id,d.expiry_date)
            OR NOT EXISTS(SELECT FROM public.kyc_manual_action a CROSS JOIN LATERAL jsonb_array_elements(a.payload::jsonb->'sourceExpiries') source
                WHERE a.action_id=c.action_id AND a.tenant_id=c.tenant_id AND a.kyc_case_id=c.kyc_case_id
                    AND source->>'documentId'=NEW.document_id::text
                    AND (NEW.source_expiry_date,NEW.source_expiry_zone,NEW.source_expiry_offset_seconds,NEW.source_expires_at)
                        IS NOT DISTINCT FROM ((source->>'sourceDate')::timestamp,source->>'zoneId',(source->>'offsetSeconds')::integer,(source->>'expiresAt')::timestamptz))
            OR NOT kyc_manual_private.source_expiry_matches(NEW.source_expiry_date,NEW.source_expiry_zone,NEW.source_expiry_offset_seconds,NEW.source_expires_at)
            OR (NEW.source_expiry_date IS NOT NULL AND (NEW.source_expiry_zone<>c.source_expiry_zone
                OR NEW.source_expires_at<=c.database_time OR NEW.valid_until>NEW.source_expires_at))
            OR NEW.collected_at>c.database_time OR NEW.valid_until<=c.database_time THEN
            RAISE EXCEPTION 'Exact original KYC document provenance required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='kyc_manual_audit' THEN
        IF NEW.action_id<>c.action_id THEN RAISE EXCEPTION 'Original KYC audit action required' USING ERRCODE='23514';END IF;
    ELSE RAISE EXCEPTION 'Unsupported KYC business table' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
ALTER FUNCTION kyc_manual_private.guard_write() OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION kyc_manual_private.guard_write() FROM PUBLIC;

ALTER TABLE public.kyc_manual_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.kyc_manual_control FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.kyc_manual_control FROM PUBLIC;
GRANT SELECT ON public.kyc_manual_control TO kyc_manual_guard,firefly_audit_writer,kyc_manual_reader;
GRANT INSERT ON public.kyc_manual_control TO firefly_audit_writer;
CREATE POLICY guard_read ON public.kyc_manual_control FOR SELECT TO kyc_manual_guard USING(true);
CREATE POLICY scoped_read ON public.kyc_manual_control FOR SELECT TO firefly_audit_writer,kyc_manual_reader USING(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE POLICY scoped_insert ON public.kyc_manual_control FOR INSERT TO firefly_audit_writer WITH CHECK(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE TRIGGER original_kyc_write BEFORE INSERT OR UPDATE OR DELETE ON public.kyc_manual_control FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.guard_write();

ALTER TABLE public.kyc_manual_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.kyc_manual_action FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.kyc_manual_action FROM PUBLIC;
GRANT SELECT ON public.kyc_manual_action TO kyc_manual_guard,firefly_audit_writer,kyc_manual_reader;
GRANT INSERT ON public.kyc_manual_action TO firefly_audit_writer;
CREATE POLICY guard_read ON public.kyc_manual_action FOR SELECT TO kyc_manual_guard USING(true);
CREATE POLICY scoped_read ON public.kyc_manual_action FOR SELECT TO firefly_audit_writer,kyc_manual_reader USING(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE POLICY scoped_insert ON public.kyc_manual_action FOR INSERT TO firefly_audit_writer WITH CHECK(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE TRIGGER original_kyc_write BEFORE INSERT OR UPDATE OR DELETE ON public.kyc_manual_action FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.guard_write();

ALTER TABLE public.kyc_manual_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.kyc_manual_evidence FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.kyc_manual_evidence FROM PUBLIC;
GRANT SELECT ON public.kyc_manual_evidence TO kyc_manual_guard,firefly_audit_writer,kyc_manual_reader;
GRANT INSERT ON public.kyc_manual_evidence TO firefly_audit_writer;
CREATE POLICY guard_read ON public.kyc_manual_evidence FOR SELECT TO kyc_manual_guard USING(true);
CREATE POLICY scoped_read ON public.kyc_manual_evidence FOR SELECT TO firefly_audit_writer,kyc_manual_reader USING(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE POLICY scoped_insert ON public.kyc_manual_evidence FOR INSERT TO firefly_audit_writer WITH CHECK(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE TRIGGER original_kyc_write BEFORE INSERT OR UPDATE OR DELETE ON public.kyc_manual_evidence FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.guard_write();

ALTER TABLE public.kyc_manual_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.kyc_manual_audit FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.kyc_manual_audit FROM PUBLIC;
GRANT SELECT ON public.kyc_manual_audit TO kyc_manual_guard,firefly_audit_writer,kyc_manual_reader;
GRANT INSERT ON public.kyc_manual_audit TO firefly_audit_writer;
CREATE POLICY guard_read ON public.kyc_manual_audit FOR SELECT TO kyc_manual_guard USING(true);
CREATE POLICY scoped_read ON public.kyc_manual_audit FOR SELECT TO firefly_audit_writer,kyc_manual_reader USING(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE POLICY scoped_insert ON public.kyc_manual_audit FOR INSERT TO firefly_audit_writer WITH CHECK(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE TRIGGER original_kyc_write BEFORE INSERT OR UPDATE OR DELETE ON public.kyc_manual_audit FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.guard_write();

GRANT UPDATE(submission_action_id,review_action_id,state,version) ON public.kyc_manual_control TO firefly_audit_writer;
CREATE POLICY scoped_update ON public.kyc_manual_control FOR UPDATE TO firefly_audit_writer
 USING(public.kyc_manual_case_matches(tenant_id,kyc_case_id)) WITH CHECK(public.kyc_manual_case_matches(tenant_id,kyc_case_id));
CREATE FUNCTION kyc_manual_private.preserve_bound_evidence() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_TABLE_NAME='kyc_verification' THEN
        IF EXISTS(SELECT FROM public.kyc_manual_control c WHERE c.kyc_case_id=OLD.kyc_verification_id)
            AND (TG_OP='DELETE' OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.kyc_verification_id IS DISTINCT FROM OLD.kyc_verification_id) THEN
            RAISE EXCEPTION 'Governed KYC case ownership is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='verification_document' THEN
        IF EXISTS(SELECT FROM public.kyc_manual_evidence e WHERE e.document_id=OLD.verification_document_id)
            AND (TG_OP='DELETE' OR (NEW.verification_document_id,NEW.kyc_verification_id,NEW.identity_document_id,NEW.document_type,
                NEW.verification_purpose,NEW.document_reference,NEW.document_system_id,NEW.expiry_date)
                IS DISTINCT FROM (OLD.verification_document_id,OLD.kyc_verification_id,OLD.identity_document_id,OLD.document_type,
                    OLD.verification_purpose,OLD.document_reference,OLD.document_system_id,OLD.expiry_date)) THEN
            RAISE EXCEPTION 'Governed KYC evidence requires a new source document revision' USING ERRCODE='23514';
        END IF;
    ELSE RAISE EXCEPTION 'Unsupported KYC source table' USING ERRCODE='42501';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
ALTER FUNCTION kyc_manual_private.preserve_bound_evidence() OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION kyc_manual_private.preserve_bound_evidence() FROM PUBLIC;
CREATE TRIGGER governed_kyc_case_owner BEFORE UPDATE OR DELETE ON public.kyc_verification FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.preserve_bound_evidence();
CREATE TRIGGER governed_kyc_document_source BEFORE UPDATE OR DELETE ON public.verification_document FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.preserve_bound_evidence();

CREATE FUNCTION kyc_manual_private.audit_text(p_text TEXT) RETURNS BYTEA
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$ SELECT int4send(octet_length(convert_to(p_text,'UTF8')))||convert_to(p_text,'UTF8') $$;
CREATE FUNCTION kyc_manual_private.action_evidence(a public.kyc_manual_action) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT encode(sha256(kyc_manual_private.audit_text('kyc.manual.decision.evidence')||int4send(1)
    ||uuid_send(a.tenant_id)||uuid_send(a.party_id)||uuid_send(a.kyc_case_id)||uuid_send(a.action_id)||uuid_send(a.receipt_id)||uuid_send(a.event_id)||uuid_send(a.selection_id)||uuid_send(a.staff_actor_id)
    ||int8send(a.resulting_version)||int8send(floor(extract(epoch FROM a.recorded_at))::bigint)
    ||int4send(((extract(epoch FROM a.recorded_at)-floor(extract(epoch FROM a.recorded_at)))*1000000000)::integer)
    ||kyc_manual_private.audit_text(a.operation)||kyc_manual_private.audit_text(a.intent_digest)||kyc_manual_private.audit_text(a.payload_digest)
    ||kyc_manual_private.audit_text(a.resulting_state)||kyc_manual_private.audit_text(a.actor_issuer)||kyc_manual_private.audit_text(a.actor_subject)
    ||kyc_manual_private.audit_text(a.actor_client_id)||kyc_manual_private.audit_text(a.platform_tenant_id)||kyc_manual_private.audit_text(a.staff_realm)||kyc_manual_private.audit_text(a.policy_revision)),'hex')
$$;
ALTER FUNCTION kyc_manual_private.audit_text(TEXT) OWNER TO kyc_manual_guard;
ALTER FUNCTION kyc_manual_private.action_evidence(public.kyc_manual_action) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION kyc_manual_private.audit_text(TEXT),kyc_manual_private.action_evidence(public.kyc_manual_action) FROM PUBLIC;

CREATE FUNCTION public.kyc_manual_pair_audit(p_nonce UUID,p_audit_nonce UUID,p_event UUID,p_sequence BIGINT,p_hash TEXT,p_replay BOOLEAN) RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c kyc_manual_private.context%ROWTYPE;a public.kyc_manual_action%ROWTYPE;b public.kyc_manual_audit%ROWTYPE;completed RECORD;
BEGIN
    SELECT * INTO c FROM kyc_manual_private.context WHERE xid=pg_current_xact_id_if_assigned() AND backend_pid=pg_backend_pid() AND login_name=session_user AND nonce=p_nonce;
    IF NOT FOUND OR session_user<>'firefly_audit_writer' OR c.purpose IN('CURRENT','RECOVER') OR c.audit_paired
        OR c.authority_until<=clock_timestamp() OR c.command_until<=clock_timestamp() OR p_replay IS DISTINCT FROM c.original_action_existed
        OR c.original_action_existed<>c.original_binding_existed THEN
        RAISE EXCEPTION 'Original completed KYC audit disposition required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO a FROM public.kyc_manual_action WHERE action_id=c.action_id AND tenant_id=c.tenant_id AND party_id=c.party_id AND kyc_case_id=c.kyc_case_id
        AND operation=c.purpose AND selection_id=c.selection_id AND intent_digest=c.intent_digest AND actor_issuer=c.issuer AND actor_subject=c.subject AND staff_actor_id=c.staff_actor_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact original KYC action evidence required' USING ERRCODE='23514';END IF;
    SELECT * INTO b FROM public.kyc_manual_audit WHERE action_id=a.action_id AND tenant_id=a.tenant_id AND kyc_case_id=a.kyc_case_id
        AND event_id=p_event AND event_id=a.event_id AND audit_sequence=p_sequence AND audit_record_hash=p_hash AND (p_replay OR audit_context_nonce=p_audit_nonce);
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact original KYC audit binding required' USING ERRCODE='23514';END IF;
    SELECT * INTO STRICT completed FROM public.firefly_audit_require_completed(p_audit_nonce,p_event,p_sequence,p_hash,p_replay);
    IF (completed.resource_tenant_id,completed.service,completed.resource_type,completed.resource_id,completed.event_id,
        completed.actor_issuer,completed.actor_subject,completed.actor_client_id,completed.actor_platform_tenant_id,completed.actor_staff_realm,completed.actor_staff_id,
        completed.action,completed.outcome,completed.process_id,completed.correlation_id,completed.causation_id,completed.occurred_seconds,completed.occurred_nanos,completed.evidence_digest,completed.policy_revision)
        IS DISTINCT FROM (a.tenant_id,'core-common-kycb-mgmt'::text,'kyc-manual-decision'::text,a.kyc_case_id,a.event_id,
            a.actor_issuer,a.actor_subject,a.actor_client_id,a.platform_tenant_id,a.staff_realm,a.staff_actor_id,
            'KYC_MANUAL_'||a.operation,'COMPLETED'::text,a.action_id,a.receipt_id,a.action_id,
            floor(extract(epoch FROM a.recorded_at))::bigint,((extract(epoch FROM a.recorded_at)-floor(extract(epoch FROM a.recorded_at)))*1000000000)::integer,
            kyc_manual_private.action_evidence(a),a.policy_revision) THEN
        RAISE EXCEPTION 'Exact completed original canonical KYC audit required' USING ERRCODE='23514';
    END IF;
    UPDATE kyc_manual_private.context SET audit_paired=true WHERE xid=c.xid AND nonce=c.nonce;
    RETURN p_nonce;
END $$;
ALTER FUNCTION public.kyc_manual_pair_audit(UUID,UUID,UUID,BIGINT,TEXT,BOOLEAN) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION public.kyc_manual_pair_audit(UUID,UUID,UUID,BIGINT,TEXT,BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.kyc_manual_pair_audit(UUID,UUID,UUID,BIGINT,TEXT,BOOLEAN) TO firefly_audit_writer;

CREATE FUNCTION public.kyc_manual_complete(p_nonce UUID) RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c kyc_manual_private.context%ROWTYPE;control public.kyc_manual_control%ROWTYPE;a public.kyc_manual_action%ROWTYPE;submission public.kyc_manual_action%ROWTYPE;
BEGIN
    SELECT * INTO c FROM kyc_manual_private.context WHERE xid=pg_current_xact_id_if_assigned() AND backend_pid=pg_backend_pid() AND login_name=session_user AND nonce=p_nonce;
    IF NOT FOUND OR c.authority_until<=clock_timestamp() OR c.command_until<=clock_timestamp()
        OR c.purpose NOT IN('CURRENT','RECOVER') AND NOT c.audit_paired THEN
        RAISE EXCEPTION 'Completed current KYC scope required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO a FROM public.kyc_manual_action WHERE action_id=c.action_id AND tenant_id=c.tenant_id AND party_id=c.party_id AND kyc_case_id=c.kyc_case_id;
    IF NOT FOUND OR NOT EXISTS(SELECT FROM public.kyc_manual_audit b WHERE b.action_id=a.action_id AND b.tenant_id=a.tenant_id AND b.event_id=a.event_id) THEN
        RAISE EXCEPTION 'Original KYC action and canonical audit required' USING ERRCODE='23514';
    END IF;
    SELECT * INTO control FROM public.kyc_manual_control WHERE kyc_case_id=c.kyc_case_id AND tenant_id=c.tenant_id AND party_id=c.party_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Original KYC control required' USING ERRCODE='23514';END IF;
    IF c.purpose NOT IN('CURRENT','RECOVER') AND NOT c.original_action_existed AND (control.state,control.version) IS DISTINCT FROM (a.resulting_state,a.resulting_version) THEN
        RAISE EXCEPTION 'Exact original KYC resulting control required' USING ERRCODE='23514';
    END IF;
    IF c.purpose='RECOVER' AND (a.actor_issuer,a.actor_subject,a.staff_actor_id) IS DISTINCT FROM (c.issuer,c.subject,c.staff_actor_id) THEN
        RAISE EXCEPTION 'Original KYC recovery actor required' USING ERRCODE='42501';
    END IF;
    IF c.purpose='CURRENT' AND (control.state<>'APPROVED' OR control.review_action_id IS DISTINCT FROM c.action_id OR a.operation<>'REVIEW' OR a.resulting_state<>'APPROVED') THEN
        RAISE EXCEPTION 'Selected KYC decision is not current' USING ERRCODE='42501';
    END IF;
    SELECT * INTO submission FROM public.kyc_manual_action WHERE action_id=CASE WHEN a.operation='SUBMIT' THEN a.action_id WHEN a.operation='REVIEW' THEN a.selection_id ELSE (SELECT CASE selected.operation WHEN 'SUBMIT' THEN selected.action_id WHEN 'REVIEW' THEN selected.selection_id END FROM public.kyc_manual_action selected WHERE selected.action_id=a.selection_id AND selected.tenant_id=c.tenant_id AND selected.party_id=c.party_id AND selected.kyc_case_id=c.kyc_case_id) END
        AND tenant_id=c.tenant_id AND party_id=c.party_id AND kyc_case_id=c.kyc_case_id AND operation='SUBMIT';
    IF NOT FOUND OR NOT EXISTS(SELECT FROM public.kyc_manual_audit b WHERE b.action_id=submission.action_id AND b.tenant_id=submission.tenant_id AND b.event_id=submission.event_id) THEN
        RAISE EXCEPTION 'Original KYC submission and audit required' USING ERRCODE='23514';
    END IF;
    IF c.purpose='CURRENT' AND (submission.payload::jsonb->>'validUntil')::timestamptz<=clock_timestamp() THEN
        RAISE EXCEPTION 'Selected KYC decision has expired' USING ERRCODE='42501';
    END IF;
    IF jsonb_typeof(submission.payload::jsonb->'sourceExpiries') IS DISTINCT FROM 'array'
        OR jsonb_array_length(submission.payload::jsonb->'sourceExpiries') IS DISTINCT FROM jsonb_array_length(submission.payload::jsonb->'evidence')
        OR jsonb_array_length(submission.payload::jsonb->'evidence') NOT BETWEEN 1 AND 32
        OR (SELECT count(*) FROM public.kyc_manual_evidence e WHERE e.submission_action_id=submission.action_id)<>jsonb_array_length(submission.payload::jsonb->'evidence')
        OR EXISTS(SELECT FROM jsonb_array_elements(submission.payload::jsonb->'evidence') item WHERE NOT EXISTS(
            SELECT FROM public.kyc_manual_evidence e JOIN public.verification_document d ON d.verification_document_id=e.document_id AND d.kyc_verification_id=e.kyc_case_id
            WHERE e.submission_action_id=submission.action_id AND e.tenant_id=c.tenant_id AND e.party_id=c.party_id AND e.kyc_case_id=c.kyc_case_id
                AND (e.document_id::text,e.document_system_id,e.document_reference,e.content_digest,e.collected_at,e.valid_until)
                    =(item->>'documentId',item->>'sourceSystem',item->>'sourceReference',item->>'contentDigest',(item->>'collectedAt')::timestamptz,(item->>'validUntil')::timestamptz)
                AND (e.identity_document_id,e.document_type,e.verification_purpose,e.document_reference,e.document_system_id,e.source_expiry_date)
                    IS NOT DISTINCT FROM (d.identity_document_id,d.document_type::text,d.verification_purpose::text,d.document_reference,d.document_system_id,d.expiry_date)
                AND EXISTS(SELECT FROM jsonb_array_elements(submission.payload::jsonb->'sourceExpiries') source
                    WHERE source->>'documentId'=e.document_id::text
                        AND (e.source_expiry_date,e.source_expiry_zone,e.source_expiry_offset_seconds,e.source_expires_at)
                            IS NOT DISTINCT FROM ((source->>'sourceDate')::timestamp,source->>'zoneId',(source->>'offsetSeconds')::integer,(source->>'expiresAt')::timestamptz))
                AND kyc_manual_private.source_expiry_matches(e.source_expiry_date,e.source_expiry_zone,e.source_expiry_offset_seconds,e.source_expires_at)
                AND (e.source_expires_at IS NULL OR (e.valid_until<=e.source_expires_at
                    AND (NOT (c.purpose='CURRENT' OR c.purpose IN('SUBMIT','REVIEW') AND NOT c.original_action_existed)
                        OR e.source_expires_at>clock_timestamp())))
                AND e.valid_until>=(submission.payload::jsonb->>'validUntil')::timestamptz)) THEN
        RAISE EXCEPTION 'Exact persisted original KYC evidence required' USING ERRCODE='23514';
    END IF;
    DELETE FROM kyc_manual_private.context WHERE xid=c.xid AND nonce=c.nonce;
    RETURN p_nonce;
END $$;
ALTER FUNCTION public.kyc_manual_complete(UUID) OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION public.kyc_manual_complete(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.kyc_manual_complete(UUID) TO firefly_audit_writer,kyc_manual_reader;
CREATE FUNCTION kyc_manual_private.require_completion() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF EXISTS(SELECT FROM kyc_manual_private.context WHERE xid=NEW.xid AND nonce=NEW.nonce) THEN
        RAISE EXCEPTION 'Completed canonical KYC audit required before commit' USING ERRCODE='23514';
    END IF;RETURN NULL;
END $$;
ALTER FUNCTION kyc_manual_private.require_completion() OWNER TO kyc_manual_guard;
REVOKE ALL ON FUNCTION kyc_manual_private.require_completion() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER kyc_manual_completion AFTER INSERT ON kyc_manual_private.context
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION kyc_manual_private.require_completion();
REVOKE CREATE ON SCHEMA public FROM kyc_manual_guard;
COMMIT;
