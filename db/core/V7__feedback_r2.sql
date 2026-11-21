-- =====================================================================
-- 2차 사용자 점검 반영 (core)
--  1) 연봉 테이블 직급군 설정(1단계 금액 + 상승률/증가액) + 프리랜서(월 100만원 단위)
--  2) 근무표: 4주 자동 로테이션, 휴무·연차, 오픈/마감/일반, 최번시, 대체 근무, 조정 메모
--  3) 물류: 모든 처리 후 물류 담당 '확인 완료'(CLOSED)
--  4) 직원 근무 마감(금액 입력) → 일 소계
-- =====================================================================

-- 1) 연봉 테이블 직급군 ----------------------------------------------------
CREATE TABLE hr.salary_grade_group (
    grade_group  VARCHAR(4)  PRIMARY KEY,
    group_name   VARCHAR(30) NOT NULL,
    base_amount  BIGINT      NOT NULL,             -- 1단계 연봉
    raise_rate   NUMERIC(7,4),                     -- 단계별 상승률 (0.03 = 3%), NULL 이면 증가액 사용
    increment    BIGINT,                           -- 단계별 증가액 (원)
    pay_unit     VARCHAR(8)  NOT NULL DEFAULT 'ANNUAL' CHECK (pay_unit IN ('ANNUAL','MONTHLY')),   -- 표시 단위 (프리랜서 = 월)
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO hr.salary_grade_group (grade_group, group_name, base_amount, increment)
SELECT grade_group, max(group_name), min(annual_salary) FILTER (WHERE step = 1),
       (max(annual_salary) FILTER (WHERE step = 2)) - (min(annual_salary) FILTER (WHERE step = 1))
FROM hr.salary_grade GROUP BY grade_group;
-- 프리랜서: 월 100만원 단위 (f1 = 월 100만원 … f20 = 월 2,000만원), 3.3% 원천징수만
INSERT INTO hr.salary_grade_group (grade_group, group_name, base_amount, increment, pay_unit) VALUES ('f', '프리랜서', 12000000, 12000000, 'MONTHLY')
ON CONFLICT DO NOTHING;
INSERT INTO hr.salary_grade (code, grade_group, group_name, step, annual_salary)
SELECT 'f' || s, 'f', '프리랜서', s, 12000000 * s FROM generate_series(1, 20) s
ON CONFLICT DO NOTHING;

-- 2) 근무표 ----------------------------------------------------------------
CREATE TABLE hr.schedule_setting (                -- 매장별 근무표 기준
    store_id     UUID PRIMARY KEY,
    open_time    TIME     NOT NULL DEFAULT '07:00',
    close_time   TIME     NOT NULL DEFAULT '22:00',
    peak_start   TIME     NOT NULL DEFAULT '11:30',  -- 최번시
    peak_end     TIME     NOT NULL DEFAULT '14:00',
    shift_hours  NUMERIC(4,1) NOT NULL DEFAULT 8,    -- 1회 근무(휴게 포함) 기본 시간
    min_open     SMALLINT NOT NULL DEFAULT 1,
    min_close    SMALLINT NOT NULL DEFAULT 1,
    min_peak     SMALLINT NOT NULL DEFAULT 2,        -- 최번시 최소 인원 (오픈·마감 포함)
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE hr.work_schedule ADD COLUMN shift_type VARCHAR(8) NOT NULL DEFAULT 'CUSTOM'
    CHECK (shift_type IN ('OPEN','CLOSE','MID','HQ','CUSTOM'));
ALTER TABLE hr.work_schedule ADD COLUMN auto BOOLEAN NOT NULL DEFAULT FALSE;           -- 자동 로테이션으로 생성
ALTER TABLE hr.work_schedule ADD COLUMN adjust_memo TEXT;                              -- 수동 조정 승인 메모
ALTER TABLE hr.work_schedule ADD COLUMN adjusted_by UUID;
ALTER TABLE hr.work_schedule ADD COLUMN adjusted_at TIMESTAMPTZ;
ALTER TABLE hr.work_schedule ADD COLUMN substitute_for UUID REFERENCES hr.employee(id); -- 대체 근무 (원래 근무자)

CREATE TABLE hr.schedule_dayoff (                 -- 휴무 지정 / 연차
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID NOT NULL,
    employee_id  UUID NOT NULL REFERENCES hr.employee(id),
    off_date     DATE NOT NULL,
    kind         VARCHAR(8) NOT NULL CHECK (kind IN ('OFF','LEAVE')),   -- OFF 휴무 희망, LEAVE 연차(긴급 포함)
    memo         TEXT,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, off_date)
);
CREATE INDEX ix_dayoff_store_date ON hr.schedule_dayoff(store_id, off_date);

CREATE TABLE hr.schedule_log (                    -- 근무표 조정 이력 (직원별 승인 메모)
    id           BIGSERIAL PRIMARY KEY,
    store_id     UUID NOT NULL,
    employee_id  UUID,
    work_date    DATE NOT NULL,
    action       VARCHAR(16) NOT NULL,              -- GENERATE, ADJUST, DELETE, LEAVE, SUBSTITUTE, REBUILD
    before_text  TEXT,
    after_text   TEXT,
    memo         TEXT,
    actor_id     UUID,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_schedule_log_store ON hr.schedule_log(store_id, work_date);

-- 3) 물류 확인 완료 ------------------------------------------------------------
DO $$
DECLARE c text;
BEGIN
  SELECT conname INTO c FROM pg_constraint WHERE conrelid = 'logistics.request'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%COMPLETED%';
  IF c IS NOT NULL THEN EXECUTE 'ALTER TABLE logistics.request DROP CONSTRAINT ' || quote_ident(c); END IF;
END $$;
ALTER TABLE logistics.request ADD CONSTRAINT request_status_check CHECK (status IN (
  'DRAFT','REQUESTED','STORE_APPROVED','LOGISTICS_APPROVED','PURCHASED','PREPARING','SHIPPED','DELIVERED',
  'PARTIALLY_RECEIVED','RECEIVED','COMPLETED','CLOSED','REJECTED','CANCELLED'));
ALTER TABLE logistics.request ADD COLUMN closed_at TIMESTAMPTZ;   -- 물류 담당 확인 완료
ALTER TABLE logistics.request ADD COLUMN closed_by UUID;
ALTER TABLE logistics.request ADD COLUMN close_memo TEXT;

-- 4) 직원 근무 마감 (일 소계) ------------------------------------------------
CREATE TABLE sales.shift_close (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID   NOT NULL,
    sales_date   DATE   NOT NULL,
    user_id      UUID   NOT NULL,
    closer_name  VARCHAR(50) NOT NULL,
    beverage     BIGINT NOT NULL DEFAULT 0,      -- 결제 금액(VAT 포함)
    dessert      BIGINT NOT NULL DEFAULT 0,
    tableware    BIGINT NOT NULL DEFAULT 0,
    etc          BIGINT NOT NULL DEFAULT 0,
    discount     BIGINT NOT NULL DEFAULT 0,
    refund       BIGINT NOT NULL DEFAULT 0,
    card         BIGINT NOT NULL DEFAULT 0,
    cash         BIGINT NOT NULL DEFAULT 0,
    other_pay    BIGINT NOT NULL DEFAULT 0,
    customers    INTEGER,
    memo         TEXT,
    applied_at   TIMESTAMPTZ,                     -- 일 매출에 반영된 시각
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_shift_close_store_date ON sales.shift_close(store_id, sales_date);
