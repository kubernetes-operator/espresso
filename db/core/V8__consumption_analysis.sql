-- 재고 소비 분석 (4차 요청): 소비된 재고를 중량(kg)으로 환산해 구분 매출과 비교
-- 1) 품목 단위당 중량(g): 재고 1단위(예: 원두 1kg 봉, 치즈케이크 10입 1박스)의 순중량
ALTER TABLE catalog.item ADD COLUMN IF NOT EXISTS net_weight_g NUMERIC(12,1) CHECK (net_weight_g IS NULL OR net_weight_g > 0);
-- 품명·단위로 알 수 있는 것은 자동 채움 (예: "원두 1kg" → 1000g, "박력분 5kg" → 5000g, "500g" → 500g)
UPDATE catalog.item SET net_weight_g = (substring(name from '([0-9]+(?:\.[0-9]+)?)\s*(?:kg|KG|Kg)'))::numeric * 1000
 WHERE net_weight_g IS NULL AND name ~ '[0-9]+(\.[0-9]+)?\s*(kg|KG|Kg)';
UPDATE catalog.item SET net_weight_g = (substring(name from '([0-9]+(?:\.[0-9]+)?)\s*g\M'))::numeric
 WHERE net_weight_g IS NULL AND name ~ '[0-9]+(\.[0-9]+)?\s*g\M';
UPDATE catalog.item SET net_weight_g = 1000 * pack_qty WHERE net_weight_g IS NULL AND lower(unit) = 'kg';

-- 2) 소비 분석 그룹: 원두(BEAN) = 커피(원두), 베이커리(BAKERY) = 디저트 + 베이킹 재료
ALTER TABLE catalog.category ADD COLUMN IF NOT EXISTS consumption_group VARCHAR(16) CHECK (consumption_group IN ('BEAN','BAKERY'));
UPDATE catalog.category SET consumption_group = 'BEAN'   WHERE code = 'COFFEE';
UPDATE catalog.category SET consumption_group = 'BAKERY' WHERE code IN ('DESSERT','BAKING_ING');
