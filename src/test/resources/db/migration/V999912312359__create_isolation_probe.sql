-- TEST-ONLY migration (lives in src/test/resources, never ships in the jar).
-- Version sorts after any real V<yyyymmddhhmm> migration forever.
--
-- Creates the probe table used by TenantIsolationTestBase to prove that a new
-- entity is tenant-safe with zero code beyond `extends TenantAwareEntity` plus
-- this RLS template, applied verbatim from V202607241000__create_tenant_and_super_admin.sql.
CREATE TABLE isolation_probe (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  label varchar(100) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

ALTER TABLE isolation_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE isolation_probe FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON isolation_probe
  USING (tenant_id::text = current_setting('app.tenant_id', true));

-- Non-superuser role for the raw-JDBC RLS tests. The Testcontainers default user is
-- a SUPERUSER and superusers bypass RLS entirely (FORCE only covers table owners),
-- so proving the policy requires connecting as an ordinary role — which is exactly
-- what the app role is in dev/prod. Test-only credential, never ships.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rls_probe') THEN
    CREATE ROLE rls_probe LOGIN PASSWORD 'rls_probe';
  END IF;
END $$;

GRANT USAGE ON SCHEMA helyx_hr TO rls_probe;
-- Grants are per-table, so every table wanting a raw-JDBC RLS proof needs its own line here.
GRANT SELECT ON isolation_probe TO rls_probe;
GRANT SELECT ON app_user TO rls_probe;
GRANT SELECT ON user_role TO rls_probe;
GRANT SELECT ON password_reset_token TO rls_probe;
GRANT SELECT ON division TO rls_probe;
GRANT SELECT ON department TO rls_probe;
GRANT SELECT ON employee TO rls_probe;
GRANT SELECT ON employee_status_history TO rls_probe;
GRANT SELECT ON employee_manager_history TO rls_probe;
GRANT SELECT ON education TO rls_probe;
GRANT SELECT ON emergency_contact TO rls_probe;
GRANT SELECT ON government_id TO rls_probe;
GRANT SELECT ON bank_detail TO rls_probe;
GRANT SELECT ON benefit TO rls_probe;
GRANT SELECT ON leave_type TO rls_probe;
GRANT SELECT ON public_holiday TO rls_probe;
GRANT SELECT ON leave_balance TO rls_probe;
