-- 식기·포장 구분 매출 계정, 비품 폐기(고가 식기 파손 등) 처분손실
SET search_path TO stl;

INSERT INTO account_subject (code, name, type, pnl_section, is_system, sort_order) VALUES
 ('406','상품매출-식기·포장','REVENUE','SALES',TRUE,102),
 ('970','유형자산처분손실(비품 파손·폐기)','EXPENSE','NON_OP_EXPENSE',TRUE,962)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE fixed_asset ADD COLUMN IF NOT EXISTS division_code  VARCHAR(16);
ALTER TABLE fixed_asset ADD COLUMN IF NOT EXISTS dispose_reason VARCHAR(200);
ALTER TABLE fixed_asset ADD COLUMN IF NOT EXISTS disposed_by    UUID;
UPDATE fixed_asset SET division_code = 'MANAGEMENT' WHERE division_code IS NULL;
