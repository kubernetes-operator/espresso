-- =====================================================================
-- 1차 사용자 점검 반영 (core)
--  1) 사이트 구분: 매장 / 본사 / 베이커리 (본사 사이트 = 본사 직원·본사 재고)
--  2) 권역: 광역자치단체(특별시·광역시·특별자치시·도) 기본 17개 + 추가 가능
--  3) 품목 코드 자동 생성(카테고리 접두어), 본사 매입 단가
--  4) 물류 기한·재발송·수령 문제 처리
--  5) 연봉 테이블(직급별 20단계), 사번 8자리 연번, 상여·퇴직금, 근무표
-- =====================================================================

-- 1) 사이트 구분 ----------------------------------------------------------
ALTER TABLE store.store ADD COLUMN site_type VARCHAR(8) NOT NULL DEFAULT 'STORE'
    CHECK (site_type IN ('STORE','HQ','BAKERY'));
-- 본사 사이트 (고정 ID): 본사 소속 직원, 본사 재고(창고)
INSERT INTO store.store (id, code, name, status, business_type, site_type)
VALUES ('00000000-0000-0000-0000-0000000000a0', 'HQ', '본사', 'OPEN', 'DIRECT', 'HQ')
ON CONFLICT DO NOTHING;

-- 2) 권역 -----------------------------------------------------------------
ALTER TABLE store.region ADD COLUMN level VARCHAR(8) NOT NULL DEFAULT 'SIDO' CHECK (level IN ('SIDO','SUB'));
INSERT INTO store.region (id, code, name, sort_order) VALUES
 ('11111111-0000-0000-0000-000000000001', 'SEOUL',    '서울특별시', 1),
 ('11111111-0000-0000-0000-000000000002', 'GYEONGGI', '경기도',     9)
ON CONFLICT (code) DO UPDATE SET name = excluded.name, sort_order = excluded.sort_order;
INSERT INTO store.region (code, name, sort_order) VALUES
 ('BUSAN',     '부산광역시',       2),
 ('DAEGU',     '대구광역시',       3),
 ('INCHEON',   '인천광역시',       4),
 ('GWANGJU',   '광주광역시',       5),
 ('DAEJEON',   '대전광역시',       6),
 ('ULSAN',     '울산광역시',       7),
 ('SEJONG',    '세종특별자치시',   8),
 ('GANGWON',   '강원특별자치도',  10),
 ('CHUNGBUK',  '충청북도',        11),
 ('CHUNGNAM',  '충청남도',        12),
 ('JEONBUK',   '전북특별자치도',  13),
 ('JEONNAM',   '전라남도',        14),
 ('GYEONGBUK', '경상북도',        15),
 ('GYEONGNAM', '경상남도',        16),
 ('JEJU',      '제주특별자치도',  17)
ON CONFLICT (code) DO UPDATE SET name = excluded.name, sort_order = excluded.sort_order;

-- 3) 품목 코드·본사 단가 ---------------------------------------------------
ALTER TABLE catalog.category ADD COLUMN sku_prefix VARCHAR(8);
UPDATE catalog.category SET sku_prefix = v.p FROM (VALUES
 ('COFFEE','CF'),('DAIRY','DA'),('SYRUP','SY'),('TEA_BASE','TB'),('COFFEE_TOOL','CT'),
 ('DESSERT','DS'),('SNACK','SN'),('BAKING_ING','BK'),('BAKING_TOOL','BT'),
 ('TABLEWARE','TW'),('PACKAGING','PK'),('DISPOSABLE','DP'),
 ('HYGIENE','HY'),('OFFICE','OF'),('IT','IT'),('TELECOM','TC'),('ETC','ET'),
 ('FURNITURE','FN'),('REFRIGERATOR','RF'),('FREEZER','FZ'),('COFFEE_MACHINE','CM'),('ICE_MAKER','IM'),('KITCHEN','KT')
) AS v(c, p) WHERE category.code = v.c;
UPDATE catalog.category SET sku_prefix = upper(left(code, 2)) WHERE sku_prefix IS NULL;
ALTER TABLE catalog.item ADD COLUMN hq_cost BIGINT CHECK (hq_cost >= 0);   -- 본사 매입 단가(공급가) — 물류 관리자 이상만 조회

-- 4) 물류 기한·재발송·수령 문제 --------------------------------------------
ALTER TABLE logistics.request ADD COLUMN due_date DATE;                         -- 처리 기한 (물류 담당 지정)
ALTER TABLE logistics.request ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'NORMAL' CHECK (kind IN ('NORMAL','RESEND'));
ALTER TABLE logistics.request ADD COLUMN parent_request_id UUID REFERENCES logistics.request(id);
ALTER TABLE logistics.request_line ADD COLUMN problem_type VARCHAR(8) CHECK (problem_type IN ('SHORT','DAMAGED','DEFECT'));
ALTER TABLE logistics.request_line ADD COLUMN problem_qty NUMERIC(12,3);
ALTER TABLE logistics.request_line ADD COLUMN resolution VARCHAR(10) CHECK (resolution IN ('RESEND','WRITE_OFF'));
ALTER TABLE logistics.request_line ADD COLUMN resend_of_line_id UUID REFERENCES logistics.request_line(id);
ALTER TABLE logistics.request_line ADD COLUMN stock_unit_cost NUMERIC(14,2);   -- 재발송 무상 라인의 재고 단가
INSERT INTO inventory.reason_code VALUES ('RECEIPT_DAMAGED','LOSS','수령 시 파손','DISPOSAL',TRUE),
                                         ('RECEIPT_DEFECT','LOSS','수령 시 불량','DISPOSAL',TRUE)
ON CONFLICT DO NOTHING;

-- 5) 인사 ------------------------------------------------------------------
-- 연봉 테이블: 직급군(접두어) × 20단계. 코드 = 접두어 + 단계 (예: s1, h3)
CREATE TABLE hr.salary_grade (
    code          VARCHAR(8)  PRIMARY KEY,
    grade_group   VARCHAR(4)  NOT NULL,           -- s 사원, m 매장 관리자, h 인사 관리자, l 물류 관리자, v 슈퍼바이저, a 총괄
    group_name    VARCHAR(30) NOT NULL,
    step          SMALLINT    NOT NULL CHECK (step BETWEEN 1 AND 99),
    annual_salary BIGINT      NOT NULL CHECK (annual_salary >= 0),
    memo          TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (grade_group, step)
);
-- 데모 금액 (운영 전 회사 연봉 테이블로 교체)
INSERT INTO hr.salary_grade (code, grade_group, group_name, step, annual_salary)
SELECT g.p || s, g.p, g.n, s, g.base + (s - 1) * g.inc
FROM (VALUES ('s','사원',26000000,800000), ('m','매장 관리자',32000000,1000000), ('h','인사 관리자',36000000,1200000),
             ('l','물류 관리자',36000000,1200000), ('v','슈퍼바이저',40000000,1300000), ('a','총괄 관리자',55000000,2000000)) AS g(p, n, base, inc),
     generate_series(1, 20) s;

ALTER TABLE hr.employment_contract ADD COLUMN salary_grade VARCHAR(8) REFERENCES hr.salary_grade(code);

-- 사번: 00000001 부터 8자리 연번
UPDATE hr.employee SET emp_no = lpad(regexp_replace(emp_no, '\D', '', 'g'), 8, '0') WHERE emp_no ~ '^E\d+$';
CREATE SEQUENCE hr.emp_no_seq;
SELECT setval('hr.emp_no_seq', greatest(1, coalesce((SELECT max(emp_no::bigint) FROM hr.employee WHERE emp_no ~ '^\d+$'), 0)),
              (SELECT count(*) > 0 FROM hr.employee WHERE emp_no ~ '^\d+$'));
ALTER TABLE hr.employee ADD COLUMN job_title VARCHAR(30);   -- 직책 표시 (예: 사원, 매장 관리자)

-- 상여금·퇴직금 (급여 월·직원별 추가 지급, 재계산해도 유지)
CREATE TABLE hr.payroll_extra (
    store_id    UUID        NOT NULL,
    period      CHAR(6)     NOT NULL,
    employee_id UUID        NOT NULL REFERENCES hr.employee(id),
    code        VARCHAR(12) NOT NULL CHECK (code IN ('BONUS','SEVERANCE')),
    amount      BIGINT      NOT NULL CHECK (amount >= 0),
    memo        TEXT,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, period, employee_id, code)
);

-- 매장별 근무표 (계획). 실제 출퇴근은 work_record
CREATE TABLE hr.work_schedule (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID NOT NULL,
    employee_id  UUID NOT NULL REFERENCES hr.employee(id),
    work_date    DATE NOT NULL,
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    memo         VARCHAR(100),
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, work_date)
);
CREATE INDEX ix_schedule_store_date ON hr.work_schedule(store_id, work_date);
