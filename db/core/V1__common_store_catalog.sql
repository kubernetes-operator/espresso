-- =====================================================================
-- core_db : 공통 + store + catalog
-- 서비스별 스키마 분리 (MSA 분리 시 스키마 단위로 이전)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS common;
CREATE SCHEMA IF NOT EXISTS store;
CREATE SCHEMA IF NOT EXISTS catalog;

-- ---------------------------------------------------------------------
-- common: 첨부·댓글(메모)·상태이력·outbox 은 문서 유형 무관 공통
-- ---------------------------------------------------------------------
CREATE TABLE common.attachment (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type    VARCHAR(32)  NOT NULL,   -- LOGISTICS_REQUEST, STOCK_ADJUSTMENT, EXPENSE ...
    doc_id      UUID         NOT NULL,
    object_key  VARCHAR(512) NOT NULL,   -- MinIO/S3 key
    file_name   VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(100),
    size_bytes  BIGINT,
    uploaded_by UUID         NOT NULL,
    uploaded_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_attach_doc ON common.attachment(doc_type, doc_id);

CREATE TABLE common.doc_comment (                -- 요구 19: 메모 스레드
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_type    VARCHAR(32) NOT NULL,
    doc_id      UUID        NOT NULL,
    parent_id   UUID REFERENCES common.doc_comment(id),
    author_id   UUID        NOT NULL,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at   TIMESTAMPTZ
);
CREATE INDEX ix_comment_doc ON common.doc_comment(doc_type, doc_id, created_at);

CREATE TABLE common.status_history (            -- 요구 22: 모든 상태 전이 시각
    id          BIGSERIAL,
    doc_type    VARCHAR(32) NOT NULL,
    doc_id      UUID        NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32) NOT NULL,
    actor_id    UUID        NOT NULL,
    memo        TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE common.status_history_default PARTITION OF common.status_history DEFAULT;
CREATE INDEX ix_status_hist_doc ON common.status_history(doc_type, doc_id, occurred_at);

CREATE TABLE common.outbox_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_schema  VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX ix_core_outbox_unpub ON common.outbox_event(created_at) WHERE published_at IS NULL;

CREATE TABLE common.doc_sequence (               -- 문서번호 채번: LR-202611-000123
    prefix   VARCHAR(8) NOT NULL,
    yyyymm   CHAR(6)    NOT NULL,
    last_no  INTEGER    NOT NULL DEFAULT 0,
    PRIMARY KEY (prefix, yyyymm)
);

-- ---------------------------------------------------------------------
-- store
-- ---------------------------------------------------------------------
CREATE TABLE store.region (                     -- 권역 (1,000개 매장 대비)
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id  UUID REFERENCES store.region(id),
    code       VARCHAR(16) NOT NULL UNIQUE,
    name       VARCHAR(50) NOT NULL,
    sort_order INTEGER     NOT NULL DEFAULT 0
);

CREATE TABLE store.store (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                  VARCHAR(16)  NOT NULL UNIQUE,
    name                  VARCHAR(100) NOT NULL,
    region_id             UUID REFERENCES store.region(id),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ONBOARDING'
                          CHECK (status IN ('ONBOARDING','OPEN','SUSPENDED','CLOSED')),
    business_type         VARCHAR(16)  NOT NULL CHECK (business_type IN ('DIRECT','FRANCHISE')),
    biz_reg_no            VARCHAR(12),             -- 사업자등록번호
    biz_name              VARCHAR(100),
    address               VARCHAR(255),
    address_detail        VARCHAR(255),
    phone                 VARCHAR(32),
    open_date             DATE,
    close_date            DATE,
    vat_display_mode      VARCHAR(10)  NOT NULL DEFAULT 'EXCLUSIVE'
                          CHECK (vat_display_mode IN ('EXCLUSIVE','INCLUSIVE')),   -- 요구 8
    owner_payroll_detail_visible BOOLEAN NOT NULL DEFAULT FALSE,               -- 점주 개인별 급여 열람
    manager_payroll_detail_visible BOOLEAN NOT NULL DEFAULT FALSE,
    settings              JSONB        NOT NULL DEFAULT '{}'::jsonb,           -- 기능 플래그 등
    attributes            JSONB        NOT NULL DEFAULT '{}'::jsonb,           -- 사용자 정의 필드 (요구 18)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID
);
CREATE INDEX ix_store_region ON store.store(region_id);

CREATE TABLE store.store_lease (                -- 임대 계약
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id         UUID   NOT NULL REFERENCES store.store(id),
    lessor_name      VARCHAR(100),
    deposit          BIGINT NOT NULL DEFAULT 0,
    monthly_rent     BIGINT NOT NULL DEFAULT 0,      -- 공급가
    maintenance_fee  BIGINT NOT NULL DEFAULT 0,
    rent_vat_type    VARCHAR(10) NOT NULL DEFAULT 'TAXABLE' CHECK (rent_vat_type IN ('TAXABLE','EXEMPT')),
    payment_day      SMALLINT CHECK (payment_day BETWEEN 1 AND 31),
    start_date       DATE   NOT NULL,
    end_date         DATE,
    memo             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store.store_onboarding (           -- 매장 생성 마법사 (요구 27)
    store_id      UUID PRIMARY KEY REFERENCES store.store(id),
    current_step  SMALLINT    NOT NULL DEFAULT 1,
    step_data     JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- 단계별 임시저장
    started_by    UUID        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- ---------------------------------------------------------------------
-- catalog (요구 24, 28, 6, 7)
-- ---------------------------------------------------------------------
CREATE TABLE catalog.division (
    code       VARCHAR(16) PRIMARY KEY,
    name       VARCHAR(20) NOT NULL,
    sort_order SMALLINT    NOT NULL
);
INSERT INTO catalog.division VALUES ('BEVERAGE','음료',1), ('DESSERT','디저트',2), ('MANAGEMENT','관리',3);

CREATE TABLE catalog.category (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id     UUID REFERENCES catalog.category(id),
    code          VARCHAR(32) NOT NULL UNIQUE,
    name          VARCHAR(50) NOT NULL,
    division_code VARCHAR(16) NOT NULL REFERENCES catalog.division(code),
    is_asset      BOOLEAN     NOT NULL DEFAULT FALSE,     -- 설비 → 감가상각·투자금
    sort_order    INTEGER     NOT NULL DEFAULT 0,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE
);

INSERT INTO catalog.category (code, name, division_code, is_asset, sort_order) VALUES
 ('COFFEE',        '커피(원두)',          'BEVERAGE',   FALSE, 10),
 ('DAIRY',         '유제품',              'BEVERAGE',   FALSE, 20),
 ('SYRUP',         '시럽·소스·파우더',    'BEVERAGE',   FALSE, 30),
 ('TEA_BASE',      '차·음료 베이스',      'BEVERAGE',   FALSE, 40),
 ('COFFEE_TOOL',   '커피 용품',           'BEVERAGE',   FALSE, 50),
 ('DESSERT',       '디저트',              'DESSERT',    FALSE, 110),
 ('SNACK',         '과자',                'DESSERT',    FALSE, 120),
 ('BAKING_ING',    '베이킹 재료',         'DESSERT',    FALSE, 130),
 ('BAKING_TOOL',   '베이킹 용품',         'DESSERT',    FALSE, 140),
 ('PACKAGING',     '포장 용품',           'MANAGEMENT', FALSE, 210),
 ('HYGIENE',       '위생·청소 용품',      'MANAGEMENT', FALSE, 220),
 ('OFFICE',        '사무 용품',           'MANAGEMENT', FALSE, 230),
 ('IT',            '전산 용품',           'MANAGEMENT', FALSE, 240),
 ('TELECOM',       '통신 용품',           'MANAGEMENT', FALSE, 250),
 ('ETC',           '기타',                'MANAGEMENT', FALSE, 290),
 ('FURNITURE',     '가구',                'MANAGEMENT', TRUE,  310),
 ('REFRIGERATOR',  '냉장고',              'MANAGEMENT', TRUE,  320),
 ('FREEZER',       '냉동고',              'MANAGEMENT', TRUE,  330),
 ('COFFEE_MACHINE','커피머신·그라인더',   'MANAGEMENT', TRUE,  340),
 ('ICE_MAKER',     '제빙기',              'MANAGEMENT', TRUE,  350),
 ('KITCHEN',       '주방기기',            'MANAGEMENT', TRUE,  360);

CREATE TABLE catalog.item (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku              VARCHAR(32)  NOT NULL UNIQUE,
    name             VARCHAR(150) NOT NULL,
    category_id      UUID         NOT NULL REFERENCES catalog.category(id),
    unit             VARCHAR(10)  NOT NULL,             -- kg, ea, box, L
    pack_qty         NUMERIC(12,3) NOT NULL DEFAULT 1,
    tax_type         VARCHAR(8)   NOT NULL DEFAULT 'TAXABLE' CHECK (tax_type IN ('TAXABLE','EXEMPT')),
    base_price       BIGINT       NOT NULL CHECK (base_price >= 0),   -- 본사 기준 공급가(VAT 제외)
    is_hq_supplied   BOOLEAN      NOT NULL DEFAULT TRUE,             -- 본사 기본 제공 물류
    is_asset         BOOLEAN      NOT NULL DEFAULT FALSE,
    shelf_life_days  INTEGER,
    storage_type     VARCHAR(10)  CHECK (storage_type IN ('ROOM','CHILLED','FROZEN')),
    barcode          VARCHAR(64),
    image_key        VARCHAR(512),
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    attributes       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_item_category ON catalog.item(category_id);
CREATE INDEX ix_item_barcode  ON catalog.item(barcode) WHERE barcode IS NOT NULL;

CREATE TABLE catalog.store_price (              -- 매장별 단가·할인 (요구 7)
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID   NOT NULL REFERENCES store.store(id),
    item_id         UUID   NOT NULL REFERENCES catalog.item(id),
    unit_price      BIGINT CHECK (unit_price >= 0),                 -- NULL = base_price 사용
    discount_type   VARCHAR(8) CHECK (discount_type IN ('RATE','AMOUNT')),
    discount_value  NUMERIC(12,4),                                  -- RATE: 0.05 = 5%, AMOUNT: 원
    valid_from      DATE   NOT NULL,
    valid_to        DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    CHECK ((discount_type IS NULL) = (discount_value IS NULL)),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_store_price_lookup ON catalog.store_price(store_id, item_id, valid_from DESC);

CREATE TABLE catalog.logistics_package (        -- 초도/기본 물류 패키지 (마법사 5단계)
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE catalog.logistics_package_item (
    package_id  UUID NOT NULL REFERENCES catalog.logistics_package(id) ON DELETE CASCADE,
    item_id     UUID NOT NULL REFERENCES catalog.item(id),
    qty         NUMERIC(12,3) NOT NULL CHECK (qty > 0),
    PRIMARY KEY (package_id, item_id)
);
