-- =====================================================================
-- core_db : approval + logistics + inventory
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS approval;
CREATE SCHEMA IF NOT EXISTS logistics;
CREATE SCHEMA IF NOT EXISTS inventory;

-- ---------------------------------------------------------------------
-- approval : 범용 결재 엔진 (요구 10, 19)
--   기본 = 전체 결재선, 총괄이 매장·금액 구간별 템플릿/지정 승인자 설정
-- ---------------------------------------------------------------------
CREATE TABLE approval.line_template (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type    VARCHAR(32) NOT NULL,      -- LOGISTICS_REQUEST, STOCK_ADJUSTMENT, PAYROLL_RUN, EXPENSE, PERIOD_ADJUST
    name        VARCHAR(100) NOT NULL,
    store_id    UUID,                      -- NULL = 전 매장 기본
    min_amount  BIGINT,                    -- NULL = 하한 없음
    max_amount  BIGINT,                    -- NULL = 상한 없음 (미만 기준)
    priority    INTEGER NOT NULL DEFAULT 0,-- 매칭 다수 시 큰 값 우선 (매장 지정 > 전사)
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,   -- doc_type 별 전체 결재선
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);
CREATE UNIQUE INDEX ux_line_default ON approval.line_template(doc_type) WHERE is_default AND is_active;

CREATE TABLE approval.line_template_step (
    template_id        UUID     NOT NULL REFERENCES approval.line_template(id) ON DELETE CASCADE,
    seq                SMALLINT NOT NULL,
    approver_type      VARCHAR(8) NOT NULL CHECK (approver_type IN ('ROLE','USER')),
    role_code          VARCHAR(32),          -- 매장/담당 스코프로 해석 (예: OWNER → 해당 매장 점주)
    user_id            UUID,                 -- 총괄이 지정한 승인자
    skip_if_requester  BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (template_id, seq),
    CHECK ((approver_type = 'ROLE' AND role_code IS NOT NULL) OR (approver_type = 'USER' AND user_id IS NOT NULL))
);

-- 기본(전체) 결재선 시드
WITH t AS (
  INSERT INTO approval.line_template (doc_type, name, is_default, priority) VALUES
   ('LOGISTICS_REQUEST','물류 요청 전체 결재', TRUE, 0),
   ('STOCK_ADJUSTMENT', '손실·결손·내부사용 전체 결재', TRUE, 0),
   ('PAYROLL_RUN',      '급여 확정 전체 결재', TRUE, 0),
   ('EXPENSE',          '비용 전체 결재', TRUE, 0)
  RETURNING id, doc_type)
INSERT INTO approval.line_template_step (template_id, seq, approver_type, role_code)
SELECT t.id, s.seq, 'ROLE', s.role_code FROM t
JOIN (VALUES
  ('LOGISTICS_REQUEST',1,'STORE_MGR'),('LOGISTICS_REQUEST',2,'OWNER'),('LOGISTICS_REQUEST',3,'LOGISTICS_MGR'),('LOGISTICS_REQUEST',4,'HQ_ADMIN'),
  ('STOCK_ADJUSTMENT',1,'STORE_MGR'),('STOCK_ADJUSTMENT',2,'OWNER'),('STOCK_ADJUSTMENT',3,'HQ_ADMIN'),
  ('PAYROLL_RUN',1,'HR_MGR'),('PAYROLL_RUN',2,'OWNER'),('PAYROLL_RUN',3,'HQ_ADMIN'),
  ('EXPENSE',1,'STORE_MGR'),('EXPENSE',2,'OWNER'),('EXPENSE',3,'HQ_ADMIN')
) AS s(doc_type, seq, role_code) ON s.doc_type = t.doc_type;

CREATE TABLE approval.request (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type      VARCHAR(32) NOT NULL,
    doc_id        UUID        NOT NULL,
    store_id      UUID,
    amount        BIGINT,                  -- 결재선 매칭 기준 금액 (VAT 포함 합계)
    template_id   UUID REFERENCES approval.line_template(id),
    status        VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'
                  CHECK (status IN ('IN_PROGRESS','APPROVED','REJECTED','WITHDRAWN')),
    current_seq   SMALLINT,
    requested_by  UUID        NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    UNIQUE (doc_type, doc_id, requested_at)
);
CREATE INDEX ix_appr_req_doc ON approval.request(doc_type, doc_id);

CREATE TABLE approval.step (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id      UUID     NOT NULL REFERENCES approval.request(id),
    seq             SMALLINT NOT NULL,
    approver_type   VARCHAR(8) NOT NULL,
    role_code       VARCHAR(32),
    assignee_ids    UUID[]   NOT NULL,     -- 결재 시점에 해석된 승인 가능자
    status          VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                    CHECK (status IN ('WAITING','PENDING','APPROVED','REJECTED','SKIPPED')),
    acted_by        UUID,
    acted_on_behalf_of UUID,               -- 위임 결재
    is_override     BOOLEAN NOT NULL DEFAULT FALSE,   -- 총괄 직권
    activated_at    TIMESTAMPTZ,           -- 이 단계 차례가 된 시각
    acted_at        TIMESTAMPTZ,
    comment         TEXT,
    UNIQUE (request_id, seq),
    CHECK (status <> 'REJECTED' OR comment IS NOT NULL)      -- 반려 사유 필수
);
CREATE INDEX ix_appr_step_inbox ON approval.step USING GIN (assignee_ids) WHERE status = 'PENDING';

CREATE TABLE approval.delegation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user   UUID NOT NULL,
    to_user     UUID NOT NULL,
    start_at    TIMESTAMPTZ NOT NULL,
    end_at      TIMESTAMPTZ NOT NULL,
    reason      TEXT,
    created_by  UUID NOT NULL,
    CHECK (end_at > start_at)
);

-- ---------------------------------------------------------------------
-- logistics (요구 1, 2, 6, 7, 8, 19, 22)
-- ---------------------------------------------------------------------
CREATE TABLE logistics.request (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_no         VARCHAR(20) NOT NULL UNIQUE,         -- LR-202611-000123
    store_id           UUID        NOT NULL,
    title              VARCHAR(150),
    status             VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                         'DRAFT','REQUESTED','STORE_APPROVED','LOGISTICS_APPROVED','PURCHASED',
                         'PREPARING','SHIPPED','DELIVERED','PARTIALLY_RECEIVED','RECEIVED',
                         'COMPLETED','REJECTED','CANCELLED')),
    is_initial_order   BOOLEAN     NOT NULL DEFAULT FALSE,   -- 매장 생성 초도 발주
    desired_date       DATE,
    assigned_mgr_id    UUID,                                 -- 담당 물류관리자
    memo               TEXT,
    taxable_supply     BIGINT NOT NULL DEFAULT 0,
    exempt_supply      BIGINT NOT NULL DEFAULT 0,
    vat_amount         BIGINT NOT NULL DEFAULT 0,
    shipping_fee       BIGINT NOT NULL DEFAULT 0,
    total_amount       BIGINT NOT NULL DEFAULT 0,
    requested_by       UUID,
    requested_at       TIMESTAMPTZ,
    approved_at        TIMESTAMPTZ,      -- 최종(물류) 승인
    shipped_at         TIMESTAMPTZ,
    delivered_at       TIMESTAMPTZ,
    received_at        TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    rejected_at        TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         UUID NOT NULL
);
CREATE INDEX ix_lr_store_status ON logistics.request(store_id, status, requested_at DESC);
CREATE INDEX ix_lr_mgr ON logistics.request(assigned_mgr_id, status);

CREATE TABLE logistics.request_line (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        UUID     NOT NULL REFERENCES logistics.request(id) ON DELETE CASCADE,
    line_no           SMALLINT NOT NULL,
    source_type       VARCHAR(8) NOT NULL CHECK (source_type IN ('HQ','EXTERNAL')),
    item_id           UUID REFERENCES catalog.item(id),          -- HQ 필수
    category_id       UUID NOT NULL REFERENCES catalog.category(id),
    item_name         VARCHAR(200) NOT NULL,                      -- 스냅샷
    unit              VARCHAR(10),
    -- 외부 구매 (요구 6)
    external_url      TEXT,
    vendor_name       VARCHAR(100),
    payer             VARCHAR(8) NOT NULL DEFAULT 'STORE' CHECK (payer IN ('STORE','HQ')),  -- 비용은 항상 매장 귀속
    price_input_mode  VARCHAR(10) NOT NULL DEFAULT 'EXCLUSIVE' CHECK (price_input_mode IN ('EXCLUSIVE','INCLUSIVE')),
    -- 가격 스냅샷 (요구 7, 8)
    tax_type          VARCHAR(8) NOT NULL CHECK (tax_type IN ('TAXABLE','EXEMPT')),
    qty               NUMERIC(12,3) NOT NULL CHECK (qty > 0),
    list_unit_price   BIGINT NOT NULL,          -- 매장 적용 단가(할인 전, 공급가)
    discount_type     VARCHAR(8),
    discount_value    NUMERIC(12,4),
    discount_amount   BIGINT NOT NULL DEFAULT 0,
    supply_amount     BIGINT NOT NULL,          -- (단가×수량 − 할인)
    vat_amount        BIGINT NOT NULL,          -- 면세 = 0
    total_amount      BIGINT NOT NULL,
    shipping_fee      BIGINT NOT NULL DEFAULT 0,
    -- 수령
    received_qty      NUMERIC(12,3),
    line_status       VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                      CHECK (line_status IN ('OPEN','PURCHASED','SHIPPED','RECEIVED','SHORT','CANCELLED')),
    purchased_at      TIMESTAMPTZ,
    memo              TEXT,
    UNIQUE (request_id, line_no),
    CHECK (source_type <> 'HQ' OR item_id IS NOT NULL),
    CHECK (source_type <> 'EXTERNAL' OR external_url IS NOT NULL),
    CHECK (tax_type <> 'EXEMPT' OR vat_amount = 0),
    CHECK (total_amount = supply_amount + vat_amount)
);

CREATE TABLE logistics.shipment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id    UUID NOT NULL REFERENCES logistics.request(id),
    carrier       VARCHAR(50),
    tracking_no   VARCHAR(64),
    shipped_by    UUID NOT NULL,
    shipped_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at  TIMESTAMPTZ,
    memo          TEXT
);
CREATE TABLE logistics.shipment_line (
    shipment_id   UUID NOT NULL REFERENCES logistics.shipment(id) ON DELETE CASCADE,
    request_line_id UUID NOT NULL REFERENCES logistics.request_line(id),
    shipped_qty   NUMERIC(12,3) NOT NULL,
    PRIMARY KEY (shipment_id, request_line_id)
);

CREATE TABLE logistics.assignment (             -- 물류관리자 담당 매장/권역
    user_id     UUID NOT NULL,
    scope_type  VARCHAR(8) NOT NULL CHECK (scope_type IN ('STORE','REGION')),
    scope_id    UUID NOT NULL,
    PRIMARY KEY (user_id, scope_type, scope_id)
);

-- ---------------------------------------------------------------------
-- inventory (요구 10, 3) — 원장 + 잔액, 이동평균
-- ---------------------------------------------------------------------
CREATE TABLE inventory.stock_balance (
    store_id    UUID NOT NULL,
    item_id     UUID NOT NULL REFERENCES catalog.item(id),
    qty         NUMERIC(14,3) NOT NULL DEFAULT 0,
    avg_cost    NUMERIC(14,2) NOT NULL DEFAULT 0,   -- 이동평균 단가(공급가)
    safety_qty  NUMERIC(12,3),                      -- 안전재고 (발주 알림)
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, item_id)
);

CREATE TABLE inventory.stock_ledger (
    id          BIGSERIAL,
    store_id    UUID NOT NULL,
    item_id     UUID NOT NULL,
    qty_delta   NUMERIC(14,3) NOT NULL,
    unit_cost   NUMERIC(14,2) NOT NULL,
    amount      BIGINT        NOT NULL,             -- 공급가 기준 금액
    reason      VARCHAR(16)   NOT NULL CHECK (reason IN
                  ('RECEIPT','SALE','LOSS','SHORTAGE','INTERNAL_USE','COUNT_ADJ','TRANSFER_IN','TRANSFER_OUT','OPENING')),
    ref_type    VARCHAR(32),
    ref_id      UUID,
    balance_qty NUMERIC(14,3) NOT NULL,
    occurred_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor_id    UUID,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);             -- 월 파티션 (pg_partman 으로 생성)
CREATE TABLE inventory.stock_ledger_default PARTITION OF inventory.stock_ledger DEFAULT;
CREATE INDEX ix_ledger_store_item ON inventory.stock_ledger(store_id, item_id, occurred_at);

CREATE TABLE inventory.reason_code (
    code      VARCHAR(24) PRIMARY KEY,
    adj_type  VARCHAR(16) NOT NULL CHECK (adj_type IN ('LOSS','SHORTAGE','INTERNAL_USE')),
    name      VARCHAR(50) NOT NULL,
    account_hint VARCHAR(16),                  -- 결산 계정 매핑 힌트
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO inventory.reason_code VALUES
 ('EXPIRED',        'LOSS',         '유통기한 경과', 'DISPOSAL', TRUE),
 ('DAMAGED',        'LOSS',         '파손',          'DISPOSAL', TRUE),
 ('DEFECT',         'LOSS',         '품질 불량',     'DISPOSAL', TRUE),
 ('COUNT_DIFF',     'SHORTAGE',     '실사 차이',     'SHRINKAGE',TRUE),
 ('DELIVERY_SHORT', 'SHORTAGE',     '배송 부족',     'SHRINKAGE',TRUE),
 ('TASTING',        'SHORTAGE',     '시음',          'TASTING',  TRUE),
 ('SAMPLE',         'SHORTAGE',     '샘플',          'TASTING',  TRUE),
 ('STAFF_DRINK',    'SHORTAGE',     '직원 음료',     'TASTING',  TRUE),
 ('CONSUMABLE_USE', 'INTERNAL_USE', '소모품 사용',   'SUPPLIES', TRUE);

CREATE TABLE inventory.stock_adjustment (       -- 손실/결손/내부사용 요청
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adj_no        VARCHAR(20) NOT NULL UNIQUE,   -- SA-202611-000045
    store_id      UUID        NOT NULL,
    adj_type      VARCHAR(16) NOT NULL CHECK (adj_type IN ('LOSS','SHORTAGE','INTERNAL_USE')),
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','REQUESTED','APPROVED','REJECTED','CANCELLED')),
    source_type   VARCHAR(32),                   -- STOCK_COUNT, LOGISTICS_RECEIPT, MANUAL
    source_id     UUID,
    total_amount  BIGINT NOT NULL DEFAULT 0,
    memo          TEXT,
    requested_by  UUID,
    requested_at  TIMESTAMPTZ,
    approved_at   TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,                   -- 재고 반영 시각
    rejected_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID NOT NULL
);
CREATE INDEX ix_adj_store ON inventory.stock_adjustment(store_id, status, requested_at DESC);

CREATE TABLE inventory.stock_adjustment_line (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adjustment_id UUID NOT NULL REFERENCES inventory.stock_adjustment(id) ON DELETE CASCADE,
    item_id       UUID NOT NULL REFERENCES catalog.item(id),
    qty           NUMERIC(12,3) NOT NULL CHECK (qty > 0),
    reason_code   VARCHAR(24) NOT NULL REFERENCES inventory.reason_code(code),
    unit_cost     NUMERIC(14,2) NOT NULL,        -- 등록 시점 이동평균
    amount        BIGINT NOT NULL,
    memo          TEXT
);

CREATE TABLE inventory.stock_count (            -- 재고 실사
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      UUID NOT NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','SUBMITTED','CLOSED')),
    started_by    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at  TIMESTAMPTZ,
    adjustment_id UUID REFERENCES inventory.stock_adjustment(id)
);
CREATE TABLE inventory.stock_count_line (
    count_id     UUID NOT NULL REFERENCES inventory.stock_count(id) ON DELETE CASCADE,
    item_id      UUID NOT NULL REFERENCES catalog.item(id),
    system_qty   NUMERIC(14,3) NOT NULL,
    counted_qty  NUMERIC(14,3) NOT NULL,
    PRIMARY KEY (count_id, item_id)
);
