-- =====================================================================
-- core_db : sales + hr + report + notify
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS sales;
CREATE SCHEMA IF NOT EXISTS hr;
CREATE SCHEMA IF NOT EXISTS report;
CREATE SCHEMA IF NOT EXISTS notify;

-- ---------------------------------------------------------------------
-- sales : 1차 수기/엑셀, 2차 POS (요구 11)
-- ---------------------------------------------------------------------
CREATE TABLE sales.import_batch (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID        NOT NULL,
    source      VARCHAR(8)  NOT NULL CHECK (source IN ('EXCEL','POS')),
    file_key    VARCHAR(512),
    row_count   INTEGER,
    error_count INTEGER,
    status      VARCHAR(12) NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED','VALIDATED','APPLIED','FAILED')),
    uploaded_by UUID        NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at  TIMESTAMPTZ
);

CREATE TABLE sales.daily_sales (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id         UUID        NOT NULL,
    sales_date       DATE        NOT NULL,
    source           VARCHAR(8)  NOT NULL CHECK (source IN ('MANUAL','EXCEL','POS')),
    import_batch_id  UUID REFERENCES sales.import_batch(id),
    status           VARCHAR(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    -- 금액은 모두 공급가/세액 분리
    gross_supply     BIGINT NOT NULL DEFAULT 0,   -- 총매출(공급가, 과세)
    exempt_supply    BIGINT NOT NULL DEFAULT 0,   -- 면세 매출
    discount_amount  BIGINT NOT NULL DEFAULT 0,   -- 매출할인(공급가)
    refund_amount    BIGINT NOT NULL DEFAULT 0,   -- 환불(공급가)
    vat_amount       BIGINT NOT NULL DEFAULT 0,
    total_amount     BIGINT NOT NULL DEFAULT 0,   -- 실 결제 합계
    card_amount      BIGINT NOT NULL DEFAULT 0,
    cash_amount      BIGINT NOT NULL DEFAULT 0,
    other_pay_amount BIGINT NOT NULL DEFAULT 0,
    customer_count   INTEGER,
    memo             TEXT,
    closed_by        UUID,
    closed_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID NOT NULL,
    UNIQUE (store_id, sales_date)
);

CREATE TABLE sales.daily_sales_division (       -- 음료/디저트/관리(기타) 구분 매출
    daily_sales_id  UUID NOT NULL REFERENCES sales.daily_sales(id) ON DELETE CASCADE,
    division_code   VARCHAR(16) NOT NULL REFERENCES catalog.division(code),
    supply_amount   BIGINT NOT NULL DEFAULT 0,
    vat_amount      BIGINT NOT NULL DEFAULT 0,
    qty             INTEGER,
    PRIMARY KEY (daily_sales_id, division_code)
);

CREATE TABLE sales.pos_connection (             -- 2차: POS 어댑터
    store_id       UUID PRIMARY KEY,
    provider       VARCHAR(32) NOT NULL,         -- 예: TOSS_PLACE, OKPOS ...
    status         VARCHAR(12) NOT NULL DEFAULT 'INACTIVE' CHECK (status IN ('INACTIVE','ACTIVE','ERROR')),
    secret_ref     VARCHAR(255),                 -- K8s Secret 이름 등 참조만 (값 저장 금지)
    last_synced_at TIMESTAMPTZ,
    last_error     TEXT
);

-- ---------------------------------------------------------------------
-- hr : 직원·근무·급여 자동 계산 (요구 25)
-- ---------------------------------------------------------------------
CREATE TABLE hr.employee (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID,                        -- auth.app_user (앱 로그인 시)
    store_id           UUID        NOT NULL,
    emp_no             VARCHAR(20) NOT NULL UNIQUE,
    name               VARCHAR(50) NOT NULL,
    rrn_enc            BYTEA,                       -- 주민등록번호 (AES-256, 앱 레벨 암호화)
    bank_name          VARCHAR(30),
    bank_account_enc   BYTEA,
    hire_date          DATE        NOT NULL,
    resign_date        DATE,
    status             VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LEAVE','RESIGNED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_emp_store ON hr.employee(store_id, status);

CREATE TABLE hr.employment_contract (           -- 개인별 계약 (이력 관리)
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id            UUID   NOT NULL REFERENCES hr.employee(id),
    employment_type        VARCHAR(12) NOT NULL CHECK (employment_type IN ('REGULAR','CONTRACT','PART_TIME','FREELANCER')),
    pay_type               VARCHAR(8)  NOT NULL CHECK (pay_type IN ('HOURLY','MONTHLY')),
    base_wage              BIGINT NOT NULL,           -- 시급 또는 월급
    weekly_contract_hours  NUMERIC(5,2) NOT NULL,
    weekly_holiday_pay     BOOLEAN NOT NULL DEFAULT TRUE,   -- 주휴 대상
    income_tax_type        VARCHAR(8) NOT NULL DEFAULT 'WAGE' CHECK (income_tax_type IN ('WAGE','BIZ_3_3')),
    national_pension       BOOLEAN NOT NULL DEFAULT TRUE,
    health_insurance       BOOLEAN NOT NULL DEFAULT TRUE,
    employment_insurance   BOOLEAN NOT NULL DEFAULT TRUE,
    tax_dependents         SMALLINT NOT NULL DEFAULT 1,     -- 간이세액표 부양가족수
    non_taxable_allowances JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 식대 등
    fixed_allowances       JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_from             DATE NOT NULL,
    valid_to               DATE
);

CREATE TABLE hr.work_record (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id    UUID NOT NULL REFERENCES hr.employee(id),
    store_id       UUID NOT NULL,
    work_date      DATE NOT NULL,
    clock_in       TIMESTAMPTZ,
    clock_out      TIMESTAMPTZ,
    break_minutes  INTEGER NOT NULL DEFAULT 0,
    regular_minutes  INTEGER NOT NULL DEFAULT 0,
    overtime_minutes INTEGER NOT NULL DEFAULT 0,
    night_minutes    INTEGER NOT NULL DEFAULT 0,   -- 22:00~06:00
    holiday_minutes  INTEGER NOT NULL DEFAULT 0,
    source         VARCHAR(8) NOT NULL CHECK (source IN ('APP','MANUAL')),
    confirmed_by   UUID,
    confirmed_at   TIMESTAMPTZ,
    memo           TEXT,
    UNIQUE (employee_id, work_date, clock_in)
);
CREATE INDEX ix_work_store_date ON hr.work_record(store_id, work_date);

CREATE TABLE hr.payroll_rate (                  -- 연도별 요율표 (총괄 갱신)
    year        SMALLINT    NOT NULL,
    code        VARCHAR(32) NOT NULL,           -- MIN_WAGE, NP_EMPLOYEE, NP_EMPLOYER, HI_EMPLOYEE, LTC_RATE, EI_EMPLOYEE, EI_EMPLOYER, IA_EMPLOYER, OVERTIME_MULT ...
    value       NUMERIC(14,6) NOT NULL,
    upper_limit BIGINT,
    lower_limit BIGINT,
    memo        TEXT,
    PRIMARY KEY (year, code)
);
CREATE TABLE hr.income_tax_table (              -- 근로소득 간이세액표
    year           SMALLINT NOT NULL,
    salary_from    BIGINT   NOT NULL,
    salary_to      BIGINT   NOT NULL,
    dependents     SMALLINT NOT NULL,
    tax_amount     BIGINT   NOT NULL,
    PRIMARY KEY (year, salary_from, dependents)
);

CREATE TABLE hr.payroll_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID    NOT NULL,
    period          CHAR(6) NOT NULL,             -- YYYYMM
    status          VARCHAR(12) NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','CALCULATED','REQUESTED','CONFIRMED','PAID','REJECTED')),
    headcount       INTEGER NOT NULL DEFAULT 0,
    total_gross     BIGINT  NOT NULL DEFAULT 0,
    total_deduction BIGINT  NOT NULL DEFAULT 0,
    total_net       BIGINT  NOT NULL DEFAULT 0,
    total_employer_contrib BIGINT NOT NULL DEFAULT 0,  -- 4대보험 사업자분 + 산재
    total_severance_accrual BIGINT NOT NULL DEFAULT 0, -- 퇴직급여 충당금
    calculated_at   TIMESTAMPTZ,
    requested_by    UUID,
    requested_at    TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, period)
);

CREATE TABLE hr.payslip (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id   UUID NOT NULL REFERENCES hr.payroll_run(id) ON DELETE CASCADE,
    employee_id      UUID NOT NULL REFERENCES hr.employee(id),
    contract_id      UUID NOT NULL REFERENCES hr.employment_contract(id),
    gross_pay        BIGINT NOT NULL,
    taxable_pay      BIGINT NOT NULL,
    total_deduction  BIGINT NOT NULL,
    net_pay          BIGINT NOT NULL,
    employer_contrib BIGINT NOT NULL DEFAULT 0,
    severance_accrual BIGINT NOT NULL DEFAULT 0,
    worked_minutes   INTEGER,
    published_at     TIMESTAMPTZ,       -- 직원 앱 발행
    viewed_at        TIMESTAMPTZ,
    UNIQUE (payroll_run_id, employee_id)
);

CREATE TABLE hr.payslip_item (
    payslip_id  UUID NOT NULL REFERENCES hr.payslip(id) ON DELETE CASCADE,
    kind        VARCHAR(10) NOT NULL CHECK (kind IN ('EARNING','DEDUCTION','EMPLOYER')),
    code        VARCHAR(32) NOT NULL,   -- BASE, WEEKLY_HOLIDAY, OVERTIME, NIGHT, HOLIDAY, MEAL / NP, HI, LTC, EI, INCOME_TAX, LOCAL_TAX, BIZ_TAX / NP_ER ...
    name        VARCHAR(50) NOT NULL,
    amount      BIGINT      NOT NULL,
    basis       JSONB,                  -- 계산 근거(시간·요율)
    PRIMARY KEY (payslip_id, kind, code)
);

CREATE TABLE hr.assignment (                    -- 인적관리자 담당 매장/권역
    user_id     UUID NOT NULL,
    scope_type  VARCHAR(8) NOT NULL CHECK (scope_type IN ('STORE','REGION')),
    scope_id    UUID NOT NULL,
    PRIMARY KEY (user_id, scope_type, scope_id)
);

CREATE TABLE hr.pii_access_log (                -- 개인정보 조회 감사
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID NOT NULL,
    employee_id UUID NOT NULL,
    field       VARCHAR(32) NOT NULL,
    purpose     VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- report : 1장 리포트 불변 스냅샷 (요구 4, 5)
-- ---------------------------------------------------------------------
CREATE TABLE report.report_snapshot (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type   VARCHAR(16) NOT NULL CHECK (report_type IN ('STORE_MONTHLY','STORE_DAILY','COMPARISON','INVESTOR')),
    store_id      UUID,                         -- COMPARISON 은 NULL
    store_ids     UUID[],
    period_from   DATE NOT NULL,
    period_to     DATE NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    data          JSONB   NOT NULL,             -- 섹션별 수치 (렌더 시 등급별 필터)
    vat_basis     VARCHAR(10) NOT NULL,
    published_by  UUID NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    pdf_keys      JSONB NOT NULL DEFAULT '{}'::jsonb   -- {role: object_key}
);
CREATE INDEX ix_report_store_period ON report.report_snapshot(store_id, period_from DESC);

CREATE TABLE report.section_visibility (         -- 등급별 섹션 노출 (총괄이 조정)
    role_code   VARCHAR(32) NOT NULL,
    section     VARCHAR(32) NOT NULL,           -- KPI, PNL, DIVISION, LOGISTICS, STOCK, LABOR_TOTAL, LABOR_DETAIL, RENT_OPEX, ROYALTY, SETTLEMENT, APPROVALS, OWN_PAYSLIP
    visible     BOOLEAN     NOT NULL,
    PRIMARY KEY (role_code, section)
);
INSERT INTO report.section_visibility (role_code, section, visible)
SELECT r, s, (r = 'HQ_ADMIN')
    OR (r = 'OWNER'         AND s <> 'OWN_PAYSLIP')          -- LABOR_DETAIL 은 매장 설정으로 추가 판정
    OR (r = 'STORE_MGR'     AND s IN ('KPI','DIVISION','LOGISTICS','STOCK','LABOR_TOTAL','RENT_OPEX','APPROVALS'))
    OR (r = 'LOGISTICS_MGR' AND s IN ('LOGISTICS','STOCK'))
    OR (r = 'HR_MGR'        AND s IN ('LABOR_TOTAL','LABOR_DETAIL'))
    OR (r = 'STAFF'         AND s IN ('OWN_PAYSLIP','APPROVALS'))
FROM unnest(ARRAY['HQ_ADMIN','OWNER','STORE_MGR','LOGISTICS_MGR','HR_MGR','STAFF','SUPERVISOR']) r,
     unnest(ARRAY['KPI','PNL','DIVISION','LOGISTICS','STOCK','LABOR_TOTAL','LABOR_DETAIL','RENT_OPEX','ROYALTY','SETTLEMENT','APPROVALS','OWN_PAYSLIP']) s;

CREATE TABLE report.access_log (
    id          BIGSERIAL PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES report.report_snapshot(id),
    viewer_id   UUID NOT NULL,
    role_code   VARCHAR(32) NOT NULL,
    format      VARCHAR(4)  NOT NULL CHECK (format IN ('WEB','PDF')),
    viewed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- notify
-- ---------------------------------------------------------------------
CREATE TABLE notify.notification (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    type        VARCHAR(32) NOT NULL,           -- APPROVAL_PENDING, APPROVED, REJECTED, SHIPPED, STATEMENT_ISSUED ...
    title       VARCHAR(150) NOT NULL,
    body        TEXT,
    link        VARCHAR(255),                   -- 앱/웹 딥링크
    store_id    UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    pushed_at   TIMESTAMPTZ,
    read_at     TIMESTAMPTZ
);
CREATE INDEX ix_notify_user_unread ON notify.notification(user_id, created_at DESC) WHERE read_at IS NULL;
