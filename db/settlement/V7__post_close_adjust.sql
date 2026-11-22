-- 마감 후 처리 (4차 사용자 요청): 월 마감 뒤 생긴 처리 건을 마감월에 반영하는 수정 결산
-- 1) 마감 기간 분개가 현재 기간으로 넘어갈 때 원래 일자·기간을 남김 → 마감월에서 "마감 후 발생 건"으로 확인·반영
ALTER TABLE stl.journal_entry ADD COLUMN IF NOT EXISTS orig_date   DATE;
ALTER TABLE stl.journal_entry ADD COLUMN IF NOT EXISTS orig_period CHAR(6);
CREATE INDEX IF NOT EXISTS ix_je_orig_period ON stl.journal_entry(store_id, orig_period) WHERE orig_period IS NOT NULL;

-- 2) 수정 결산 이력: 결산 요약 버전마다 누가·왜 고쳤는지
ALTER TABLE stl.settlement_summary ADD COLUMN IF NOT EXISTS revised_by  UUID;
ALTER TABLE stl.settlement_summary ADD COLUMN IF NOT EXISTS revise_memo TEXT;

-- 3) 마감 후 처리 기록 (발생 건 반영 LATE / 직접 조정 ADJUST / 재계산 RECALC)
CREATE TABLE stl.close_adjustment (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID        NOT NULL,
    period       CHAR(6)     NOT NULL,
    kind         VARCHAR(8)  NOT NULL CHECK (kind IN ('LATE','ADJUST','RECALC')),
    account_code VARCHAR(16),
    counter_code VARCHAR(16),
    amount       BIGINT      NOT NULL DEFAULT 0,
    entry_date   DATE,
    description  VARCHAR(255),
    memo         TEXT        NOT NULL,
    entry_id     UUID,
    royalty_diff BIGINT      NOT NULL DEFAULT 0,
    version      INTEGER,
    created_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_close_adj ON stl.close_adjustment(store_id, period);
