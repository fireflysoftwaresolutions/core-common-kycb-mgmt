\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE :"reproofing_owner";
CREATE TABLE identity_reproofing_case (
 case_id uuid PRIMARY KEY, provider_start_id uuid NOT NULL UNIQUE,
 binding text NOT NULL, issuer_nonce_key char(64) NOT NULL UNIQUE, subject_budget_key char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK (status IN ('PENDING','VERIFIED','CONSUMED','EXECUTED','DENIED')),
 provider_id varchar(512) NOT NULL, policy_id varchar(512) NOT NULL, provider_session_id varchar(512),
 expires_at timestamptz NOT NULL, authorization_id uuid UNIQUE, execution_id uuid UNIQUE,
 evidence_id varchar(512), evidence_digest varchar(512), grant_expires_at timestamptz,
 confirmation_id uuid, reserved_until timestamptz, failures integer NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 3),
 CHECK ((authorization_id IS NULL AND execution_id IS NULL AND grant_expires_at IS NULL) OR
        (authorization_id IS NOT NULL AND execution_id IS NOT NULL AND grant_expires_at IS NOT NULL AND grant_expires_at<=expires_at))
);
CREATE TABLE identity_reproofing_budget (subject_budget_key char(64) PRIMARY KEY, failures integer NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 3));
CREATE TABLE identity_reproofing_audit (case_id uuid NOT NULL REFERENCES identity_reproofing_case(case_id), event varchar(64) NOT NULL, at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(case_id,event));
REVOKE ALL ON identity_reproofing_case,identity_reproofing_budget,identity_reproofing_audit FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON identity_reproofing_case,identity_reproofing_budget TO :"reproofing_runtime";
GRANT SELECT,INSERT ON identity_reproofing_audit TO :"reproofing_runtime";
COMMIT;
