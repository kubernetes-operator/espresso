-- 투자 항목 자금 출처: 투자자 출자금 / 본사 지원금 (두 금액의 합 = 매장 구성 총액)
ALTER TABLE stl.investment_item ADD COLUMN funding_source VARCHAR(10) NOT NULL DEFAULT 'HQ'
    CHECK (funding_source IN ('INVESTOR','HQ'));
