-- 재고 소비 분석 2차: 우유·시럽 그룹 추가, 용량(ml) 품목도 같은 칸(net_weight_g = g 또는 ml)으로 환산
ALTER TABLE catalog.category DROP CONSTRAINT IF EXISTS category_consumption_group_check;
ALTER TABLE catalog.category ADD CONSTRAINT category_consumption_group_check CHECK (consumption_group IN ('BEAN','BAKERY','MILK','SYRUP'));
UPDATE catalog.category SET consumption_group = 'MILK'  WHERE code = 'DAIRY';
UPDATE catalog.category SET consumption_group = 'SYRUP' WHERE code = 'SYRUP';
COMMENT ON COLUMN catalog.item.net_weight_g IS '재고 1단위의 순중량(g) 또는 용량(ml, 1ml≈1g로 환산)';
UPDATE catalog.item SET net_weight_g = (substring(name from '([0-9]+(?:\.[0-9]+)?)\s*(?:L|ℓ)(?![a-zA-Z])'))::numeric * 1000
 WHERE net_weight_g IS NULL AND name ~ '[0-9]+(\.[0-9]+)?\s*(L|ℓ)(?![a-zA-Z])';
UPDATE catalog.item SET net_weight_g = (substring(name from '([0-9]+(?:\.[0-9]+)?)\s*(?:ml|mL|ML)'))::numeric
 WHERE net_weight_g IS NULL AND name ~ '[0-9]+(\.[0-9]+)?\s*(ml|mL|ML)';
