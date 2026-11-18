-- =====================================================================
-- settlement_db : 회계·결산·로열티·본사 정산서·세무·투자 (독립 클러스터, 요구 13)
-- 다른 서비스 데이터는 이벤트로만 수신 → 자체 원장 구성
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS stl;
SET search_path TO stl;

-- 계정과목 (요구 3, 18) -----------------------------------------------------
CREATE TABLE account_subject (
    code           VARCHAR(16) PRIMARY KEY,
    name           VARCHAR(50) NOT NULL,
    type           VARCHAR(10) NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    pnl_section    VARCHAR(20) CHECK (pnl_section IN
                     ('SALES','COGS','LABOR','RENT','UTILITY','INTERNAL_USE','OPERATING','STOCK_LOSS',
                      'DEPRECIATION','ROYALTY','NON_OP_INCOME','NON_OP_EXPENSE')),
    parent_code    VARCHAR(16) REFERENCES account_subject(code),
    external_code  VARCHAR(16),          -- 더존 등 외부 회계 프로그램 코드 매핑
    is_system      BOOLEAN NOT NULL DEFAULT FALSE,   -- 시스템 필수 (삭제 불가)
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order     INTEGER NOT NULL DEFAULT 0
);

INSERT INTO account_subject (code, name, type, pnl_section, is_system, sort_order) VALUES
 ('101','현금','ASSET',NULL,TRUE,10),
 ('103','보통예금','ASSET',NULL,TRUE,11),
 ('108','외상매출금(카드)','ASSET',NULL,TRUE,12),
 ('135','부가세대급금','ASSET',NULL,TRUE,13),
 ('146','상품(재고)','ASSET',NULL,TRUE,14),
 ('206','비품(설비)','ASSET',NULL,TRUE,20),
 ('207','감가상각누계액','ASSET',NULL,TRUE,21),
 ('232','임차보증금','ASSET',NULL,TRUE,22),
 ('251','외상매입금','LIABILITY',NULL,TRUE,30),
 ('253','미지급금(본사)','LIABILITY',NULL,TRUE,31),
 ('254','예수금(급여공제)','LIABILITY',NULL,TRUE,32),
 ('255','부가세예수금','LIABILITY',NULL,TRUE,33),
 ('295','퇴직급여충당부채','LIABILITY',NULL,TRUE,34),
 ('331','투자금(출자)','EQUITY',NULL,TRUE,40),
 ('401','상품매출-음료','REVENUE','SALES',TRUE,100),
 ('402','상품매출-디저트','REVENUE','SALES',TRUE,101),
 ('403','상품매출-기타','REVENUE','SALES',TRUE,102),
 ('404','매출할인','REVENUE','SALES',TRUE,103),
 ('405','매출환입','REVENUE','SALES',TRUE,104),
 ('451','상품매출원가','EXPENSE','COGS',TRUE,110),
 ('801','급여','EXPENSE','LABOR',TRUE,200),
 ('802','상여금','EXPENSE','LABOR',TRUE,201),
 ('803','제수당','EXPENSE','LABOR',TRUE,202),
 ('804','잡급(아르바이트)','EXPENSE','LABOR',TRUE,203),
 ('806','퇴직급여','EXPENSE','LABOR',TRUE,204),
 ('811','복리후생비(4대보험 사업자분)','EXPENSE','LABOR',TRUE,205),
 ('819','지급임차료','EXPENSE','RENT',TRUE,300),
 ('837','관리비','EXPENSE','RENT',TRUE,301),
 ('815','수도광열비(전기·수도·가스)','EXPENSE','UTILITY',TRUE,400),
 ('814','통신비','EXPENSE','UTILITY',TRUE,401),
 ('830','소모품비(내부사용)','EXPENSE','INTERNAL_USE',TRUE,500),
 ('820','수선비','EXPENSE','OPERATING',TRUE,600),
 ('831','지급수수료(카드·배달·PG)','EXPENSE','OPERATING',TRUE,601),
 ('833','광고선전비','EXPENSE','OPERATING',TRUE,602),
 ('821','보험료','EXPENSE','OPERATING',FALSE,603),
 ('817','세금과공과','EXPENSE','OPERATING',TRUE,604),
 ('822','차량유지비','EXPENSE','OPERATING',FALSE,605),
 ('825','교육훈련비','EXPENSE','OPERATING',FALSE,606),
 ('848','잡비','EXPENSE','OPERATING',TRUE,607),
 ('861','재고자산폐기손실(손실)','EXPENSE','STOCK_LOSS',TRUE,700),
 ('862','재고자산감모손실(결손)','EXPENSE','STOCK_LOSS',TRUE,701),
 ('863','시음·샘플·직원음료(결손)','EXPENSE','STOCK_LOSS',TRUE,702),
 ('818','감가상각비','EXPENSE','DEPRECIATION',TRUE,800),
 ('870','지급로열티','EXPENSE','ROYALTY',TRUE,900),
 ('901','이자수익','REVENUE','NON_OP_INCOME',TRUE,950),
 ('930','잡이익','REVENUE','NON_OP_INCOME',TRUE,951),
 ('931','이자비용','EXPENSE','NON_OP_EXPENSE',TRUE,960),
 ('980','잡손실','EXPENSE','NON_OP_EXPENSE',TRUE,961);

-- 결산 기간 마감 -------------------------------------------------------------
CREATE TABLE period_close (
    store_id     UUID    NOT NULL,
    period       CHAR(6) NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSING','CLOSED')),
    closed_by    UUID,
    closed_at    TIMESTAMPTZ,
    reopened_by  UUID,
    reopened_at  TIMESTAMPTZ,
    PRIMARY KEY (store_id, period)
);

-- 분개 원장 -----------------------------------------------------------------
CREATE TABLE journal_entry (
    id            UUID    NOT NULL DEFAULT gen_random_uuid(),
    store_id      UUID    NOT NULL,
    entry_date    DATE    NOT NULL,
    period        CHAR(6) NOT NULL,
    source_type   VARCHAR(32) NOT NULL,   -- SALES_DAILY, LOGISTICS_RECEIPT, STOCK_ADJ, PAYROLL, EXPENSE, ROYALTY, DEPRECIATION, COGS_CLOSE, ADJUSTMENT
    source_id     UUID,
    description   VARCHAR(255),
    is_adjustment BOOLEAN NOT NULL DEFAULT FALSE,   -- 마감 후 조정 분개
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    PRIMARY KEY (id, entry_date)
) PARTITION BY RANGE (entry_date);
CREATE TABLE journal_entry_default PARTITION OF journal_entry DEFAULT;
CREATE INDEX ix_je_store_period ON journal_entry(store_id, period);
CREATE INDEX ix_je_source ON journal_entry(source_type, source_id);

CREATE TABLE journal_line (
    id            BIGSERIAL,
    entry_id      UUID    NOT NULL,
    entry_date    DATE    NOT NULL,
    store_id      UUID    NOT NULL,
    period        CHAR(6) NOT NULL,
    account_code  VARCHAR(16) NOT NULL REFERENCES account_subject(code),
    debit         BIGINT  NOT NULL DEFAULT 0 CHECK (debit  >= 0),
    credit        BIGINT  NOT NULL DEFAULT 0 CHECK (credit >= 0),
    division_code VARCHAR(16),            -- BEVERAGE/DESSERT/MANAGEMENT
    memo          VARCHAR(255),
    PRIMARY KEY (id, entry_date),
    FOREIGN KEY (entry_id, entry_date) REFERENCES journal_entry(id, entry_date),
    CHECK ((debit = 0) <> (credit = 0))
) PARTITION BY RANGE (entry_date);
CREATE TABLE journal_line_default PARTITION OF journal_line DEFAULT;
CREATE INDEX ix_jl_store_period_acct ON journal_line(store_id, period, account_code);

-- 비용: 임대·공과금·운영비 (요구 3) -------------------------------------------
CREATE TABLE expense (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_no     VARCHAR(20) NOT NULL UNIQUE,
    store_id       UUID   NOT NULL,
    expense_kind   VARCHAR(12) NOT NULL CHECK (expense_kind IN ('RENT','UTILITY','OPERATING','NON_OPERATING')),
    account_code   VARCHAR(16) NOT NULL REFERENCES account_subject(code),
    vendor_name    VARCHAR(100),
    vendor_biz_no  VARCHAR(12),
    expense_date   DATE   NOT NULL,
    usage_from     DATE,                 -- 공과금 사용 기간
    usage_to       DATE,
    tax_type       VARCHAR(8)  NOT NULL CHECK (tax_type IN ('TAXABLE','EXEMPT')),
    supply_amount  BIGINT NOT NULL,
    vat_amount     BIGINT NOT NULL,
    total_amount   BIGINT NOT NULL,
    payer          VARCHAR(8)  NOT NULL DEFAULT 'STORE' CHECK (payer IN ('STORE','HQ')),
    evidence_type  VARCHAR(16) CHECK (evidence_type IN ('TAX_INVOICE','CARD','CASH_RECEIPT','BILL','NONE')),
    tax_invoice_id UUID,
    recurring_id   UUID,
    status         VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','REQUESTED','APPROVED','REJECTED','CANCELLED')),
    memo           TEXT,
    requested_by   UUID,
    requested_at   TIMESTAMPTZ,
    approved_at    TIMESTAMPTZ,
    created_by     UUID NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (tax_type <> 'EXEMPT' OR vat_amount = 0),
    CHECK (total_amount = supply_amount + vat_amount)
);
CREATE INDEX ix_expense_store_date ON expense(store_id, expense_date);

CREATE TABLE recurring_expense (                -- 월세·관리비 등 자동 생성
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      UUID NOT NULL,
    expense_kind  VARCHAR(12) NOT NULL,
    account_code  VARCHAR(16) NOT NULL REFERENCES account_subject(code),
    name          VARCHAR(100) NOT NULL,
    tax_type      VARCHAR(8)  NOT NULL,
    supply_amount BIGINT NOT NULL,
    day_of_month  SMALLINT NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
    valid_from    DATE NOT NULL,
    valid_to      DATE
);

-- 로열티 (매장별, 총괄 설정) ---------------------------------------------------
CREATE TABLE royalty_policy (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      UUID NOT NULL,
    type          VARCHAR(16) NOT NULL CHECK (type IN ('RATE','FIXED','RATE_PLUS_FIXED','TIERED','NONE')),
    base          VARCHAR(24) NOT NULL DEFAULT 'NET_SALES_EXCL_VAT'
                  CHECK (base IN ('NET_SALES_EXCL_VAT','NET_SALES_INCL_VAT','GROSS_SALES_EXCL_VAT','GROSS_SALES_INCL_VAT')),
    rate          NUMERIC(7,5),            -- 0.03000 = 3%
    fixed_amount  BIGINT,
    min_amount    BIGINT,
    max_amount    BIGINT,
    tiers         JSONB,                   -- [{"from":0,"to":30000000,"rate":0.03}, ...]
    prorate_daily BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from    DATE NOT NULL,
    valid_to      DATE,
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_royalty_store ON royalty_policy(store_id, valid_from DESC);

-- 월 결산 요약 (요구 11) ------------------------------------------------------
CREATE TABLE settlement_summary (
    store_id          UUID    NOT NULL,
    period            CHAR(6) NOT NULL,
    version           INTEGER NOT NULL DEFAULT 1,
    net_sales         BIGINT  NOT NULL,   -- 공급가 기준
    sales_vat         BIGINT  NOT NULL,
    cogs              BIGINT  NOT NULL,
    opening_stock     BIGINT  NOT NULL,
    purchases         BIGINT  NOT NULL,
    closing_stock     BIGINT  NOT NULL,
    gross_profit      BIGINT  NOT NULL,
    labor             BIGINT  NOT NULL,
    rent              BIGINT  NOT NULL,
    utility           BIGINT  NOT NULL,
    internal_use      BIGINT  NOT NULL,
    operating_exp     BIGINT  NOT NULL,
    stock_loss        BIGINT  NOT NULL,   -- 손실+결손(시음 포함)
    depreciation      BIGINT  NOT NULL,
    operating_profit  BIGINT  NOT NULL,
    royalty           BIGINT  NOT NULL,
    non_operating     BIGINT  NOT NULL,   -- 영업외 순액
    settlement_amount BIGINT  NOT NULL,   -- 결산액
    by_division       JSONB   NOT NULL DEFAULT '{}'::jsonb,
    calculated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, period, version)
);

-- 본사-매장 정산서 + 입금 확인 --------------------------------------------------
CREATE TABLE hq_statement (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_no    VARCHAR(20) NOT NULL UNIQUE,     -- HS-202611-000012
    store_id        UUID    NOT NULL,
    period          CHAR(6) NOT NULL,
    supply_amount   BIGINT  NOT NULL DEFAULT 0,
    vat_amount      BIGINT  NOT NULL DEFAULT 0,
    total_amount    BIGINT  NOT NULL DEFAULT 0,
    paid_amount     BIGINT  NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','ISSUED','PARTIALLY_PAID','PAID','OVERDUE','CANCELLED')),
    due_date        DATE,
    issued_by       UUID,
    issued_at       TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,      -- 완납 확인 시각
    tax_invoice_id  UUID,
    memo            TEXT,
    UNIQUE (store_id, period)
);
CREATE TABLE hq_statement_line (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_id  UUID NOT NULL REFERENCES hq_statement(id) ON DELETE CASCADE,
    line_type     VARCHAR(16) NOT NULL CHECK (line_type IN ('ROYALTY','HQ_PAID_PURCHASE','HQ_SUPPLY','OTHER')),
    description   VARCHAR(200) NOT NULL,
    ref_type      VARCHAR(32),
    ref_id        UUID,
    tax_type      VARCHAR(8) NOT NULL,
    supply_amount BIGINT NOT NULL,
    vat_amount    BIGINT NOT NULL,
    total_amount  BIGINT NOT NULL
);
CREATE TABLE hq_statement_payment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_id  UUID   NOT NULL REFERENCES hq_statement(id),
    amount        BIGINT NOT NULL CHECK (amount > 0),
    paid_date     DATE   NOT NULL,
    method        VARCHAR(12) NOT NULL CHECK (method IN ('TRANSFER','CARD','OFFSET','OTHER')),
    depositor     VARCHAR(50),
    reported_by   UUID,                 -- 매장이 입금 알림 등록
    reported_at   TIMESTAMPTZ,
    confirmed_by  UUID,                 -- 본사 확인
    confirmed_at  TIMESTAMPTZ,
    memo          TEXT
);

-- 전자세금계산서 / 세무 내보내기 ------------------------------------------------
CREATE TABLE tax_invoice (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    direction          VARCHAR(8)  NOT NULL CHECK (direction IN ('ISSUE','RECEIVE')),
    store_id           UUID,
    supplier_biz_no    VARCHAR(12) NOT NULL,
    supplier_name      VARCHAR(100),
    buyer_biz_no       VARCHAR(12) NOT NULL,
    buyer_name         VARCHAR(100),
    write_date         DATE   NOT NULL,
    tax_type           VARCHAR(8) NOT NULL CHECK (tax_type IN ('TAXABLE','EXEMPT','ZERO')),
    supply_amount      BIGINT NOT NULL,
    vat_amount         BIGINT NOT NULL,
    total_amount       BIGINT NOT NULL,
    nts_approval_no    VARCHAR(32) UNIQUE,     -- 국세청 승인번호
    provider           VARCHAR(32),            -- 발행 대행사
    provider_ref       VARCHAR(64),
    status             VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ISSUED','SENT_NTS','FAILED','CANCELLED','COLLECTED')),
    matched_type       VARCHAR(32),            -- EXPENSE, LOGISTICS_LINE, HQ_STATEMENT
    matched_id         UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_tax_invoice_store ON tax_invoice(store_id, write_date);

CREATE TABLE accounting_export (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target       VARCHAR(16) NOT NULL CHECK (target IN ('DOUZONE','CSV','EXCEL','VAT_SUMMARY')),
    period_from  CHAR(6) NOT NULL,
    period_to    CHAR(6) NOT NULL,
    store_ids    UUID[],
    file_key     VARCHAR(512),
    requested_by UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

-- 자산·감가상각 ------------------------------------------------------------
CREATE TABLE fixed_asset (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id           UUID   NOT NULL,
    name               VARCHAR(150) NOT NULL,
    category_code      VARCHAR(32),            -- catalog.category.code (FURNITURE, REFRIGERATOR ...)
    acquired_date      DATE   NOT NULL,
    cost               BIGINT NOT NULL,
    residual_value     BIGINT NOT NULL DEFAULT 0,
    useful_life_months SMALLINT NOT NULL,
    method             VARCHAR(8) NOT NULL DEFAULT 'SL' CHECK (method IN ('SL','DB')),
    source_type        VARCHAR(32),            -- ONBOARDING, LOGISTICS_REQUEST
    source_id          UUID,
    disposed_date      DATE
);

-- 투자금 (총괄 전용, 요구 14, 15) ----------------------------------------------
CREATE TABLE investment_item (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID   NOT NULL,
    category    VARCHAR(16) NOT NULL CHECK (category IN ('INTERIOR','EQUIPMENT','DEPOSIT','PREMIUM','INITIAL_STOCK','OTHER')),
    description VARCHAR(200) NOT NULL,
    amount      BIGINT NOT NULL,
    invested_on DATE   NOT NULL,
    asset_id    UUID REFERENCES fixed_asset(id),
    created_by  UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_invest_store ON investment_item(store_id);

CREATE TABLE investor (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID UNIQUE,                    -- auth.app_user (INVESTOR 등급)
    display_name VARCHAR(50) NOT NULL,          -- 실명 대신 표시명 사용 가능
    memo        TEXT
);

CREATE TABLE investor_disclosure (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id   UUID NOT NULL REFERENCES investor(id),
    store_id      UUID NOT NULL,
    mode          VARCHAR(12) NOT NULL CHECK (mode IN ('TOTAL','FIXED_AMOUNT','RATIO')),
    fixed_amount  BIGINT,
    ratio         NUMERIC(7,5),
    show_settlement BOOLEAN NOT NULL DEFAULT FALSE,   -- 결산액·회수율 공개 여부
    visible_from  DATE NOT NULL DEFAULT CURRENT_DATE,
    visible_to    DATE,
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (mode <> 'FIXED_AMOUNT' OR fixed_amount IS NOT NULL),
    CHECK (mode <> 'RATIO' OR ratio IS NOT NULL)
);

-- 이벤트 수신 멱등성 / 감사 --------------------------------------------------------
CREATE TABLE inbound_event (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(64) NOT NULL,           -- INVESTMENT_VIEW, ROYALTY_CHANGE, PERIOD_REOPEN ...
    target_type VARCHAR(32),
    target_id   UUID,
    before_data JSONB,
    after_data  JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
