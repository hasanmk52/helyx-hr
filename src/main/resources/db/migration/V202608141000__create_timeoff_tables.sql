-- Leave types, public holidays, and leave balances (PRD §21, sub-phase 1.5). All tenant-scoped:
-- tenant_id NOT NULL plus the RLS template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).
--
-- No department scoping on leave_type: PRD §12.1/§21 model it as tenant-wide only.

CREATE TABLE leave_type (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  name varchar(100) NOT NULL,
  icon varchar(50),
  color varchar(7),
  paid boolean NOT NULL DEFAULT true,
  allows_half_day boolean NOT NULL DEFAULT true,
  allows_backdated boolean NOT NULL DEFAULT false,
  requires_approval boolean NOT NULL DEFAULT true,
  default_annual_allowance numeric(5, 2) NOT NULL DEFAULT 0,
  description text,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE public_holiday (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  date date NOT NULL,
  name varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, date, name)
);

CREATE TABLE leave_balance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  leave_type_id uuid NOT NULL REFERENCES leave_type (id),
  year int NOT NULL,
  granted numeric(5, 2) NOT NULL,
  used numeric(5, 2) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- Backstops BalanceService's idempotency check (PRD §12.2): one balance row per
  -- employee/leave-type/year no matter how many grant triggers fire.
  UNIQUE (tenant_id, employee_id, leave_type_id, year)
);

CREATE INDEX idx_leave_balance_employee_id ON leave_balance (employee_id);
CREATE INDEX idx_leave_balance_leave_type_id ON leave_balance (leave_type_id);

-- ============================================================================
-- RLS — the template block from V202607241000, applied to each table above.
-- FORCE is critical: the app role OWNS these tables, and Postgres exempts table
-- owners from RLS unless it is forced.
-- current_setting(..., true) returns NULL when app.tenant_id is unset, so each
-- policy denies ALL rows by default — no tenant context means no data.
-- ============================================================================

ALTER TABLE leave_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_type
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE public_holiday ENABLE ROW LEVEL SECURITY;
ALTER TABLE public_holiday FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public_holiday
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE leave_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_balance
  USING (tenant_id::text = current_setting('app.tenant_id', true));
