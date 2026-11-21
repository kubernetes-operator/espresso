-- 2차 사용자 점검 반영 (settlement)
-- 1) 비용 구분 '영업외'(NON_OPERATING, 13자) 저장 오류 수정
ALTER TABLE stl.expense ALTER COLUMN expense_kind TYPE VARCHAR(16);

-- 2) 투자자 출자 항목의 투자자 지정 → 이익금 배분 비율 = 투자자 출자 / (투자자 출자 + 본사 지원)
ALTER TABLE stl.investment_item ADD COLUMN investor_id UUID REFERENCES stl.investor(id);

-- 3) 투자자 지급액 결정 (월별)
CREATE TABLE stl.investor_payout (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id      UUID        NOT NULL REFERENCES stl.investor(id),
    store_id         UUID        NOT NULL,
    period           CHAR(6)     NOT NULL,
    profit           BIGINT      NOT NULL,          -- 배분 기준 이익(결산액)
    share_ratio      NUMERIC(9,6) NOT NULL,         -- 투자자 출자 비율
    calculated_amount BIGINT     NOT NULL,          -- 이익 × 비율 (음수면 0)
    decided_amount   BIGINT      NOT NULL CHECK (decided_amount >= 0),
    status           VARCHAR(8)  NOT NULL DEFAULT 'DECIDED' CHECK (status IN ('DECIDED','PAID')),
    memo             TEXT,
    decided_by       UUID        NOT NULL,
    decided_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at          TIMESTAMPTZ,
    UNIQUE (investor_id, store_id, period)
);

-- 4) 주 마감 (월~일, 일요일 24시 자동). 기간을 잠그지 않는 주간 확정 실적 스냅샷
CREATE TABLE stl.week_close (
    store_id     UUID    NOT NULL,
    week_start   DATE    NOT NULL,      -- 월요일
    week_end     DATE    NOT NULL,      -- 일요일
    net_sales    BIGINT  NOT NULL,
    operating_profit BIGINT NOT NULL,
    data         JSONB   NOT NULL,
    auto         BOOLEAN NOT NULL DEFAULT TRUE,
    closed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, week_start),
    CHECK (extract(isodow from week_start) = 1)
);
