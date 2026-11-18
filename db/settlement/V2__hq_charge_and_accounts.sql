-- 본사 청구 원천 (정산서 라인이 되는 항목) + 운반비 계정
SET search_path TO stl;

INSERT INTO account_subject (code, name, type, pnl_section, is_system, sort_order) VALUES
 ('824','운반비(배송비)','EXPENSE','OPERATING',TRUE,608),
 ('262','미지급비용(급여)','LIABILITY',NULL,TRUE,35)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE hq_charge (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      UUID    NOT NULL,
    period        CHAR(6) NOT NULL,
    charge_type   VARCHAR(16) NOT NULL CHECK (charge_type IN ('HQ_SUPPLY','HQ_PAID_PURCHASE','ROYALTY','OTHER')),
    description   VARCHAR(200) NOT NULL,
    ref_type      VARCHAR(32),
    ref_id        UUID,
    tax_type      VARCHAR(8) NOT NULL,
    supply_amount BIGINT NOT NULL,
    vat_amount    BIGINT NOT NULL,
    total_amount  BIGINT NOT NULL,
    statement_id  UUID REFERENCES hq_statement(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_hq_charge_open ON hq_charge(store_id, period) WHERE statement_id IS NULL;

-- 결산 요약에 마감 시점 기말재고·투자 회수 참고값
ALTER TABLE settlement_summary ADD COLUMN IF NOT EXISTS direct_purchase BIGINT NOT NULL DEFAULT 0;
ALTER TABLE expense ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;
ALTER TABLE expense ADD COLUMN IF NOT EXISTS reject_comment TEXT;
