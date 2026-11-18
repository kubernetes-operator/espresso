-- 요구 28 개정: 물류 구분 4개 (음료 / 디저트 / 식기·포장 / 관리)
--  · 식기(머그·글라스·접시·커트러리) · 포장(테이크아웃 백·캐리어·디저트 박스) · 일회용품(컵·뚜껑·빨대·슬리브·냅킨·스푼·포크)
--  · 식기 파손 = 손실(식기 파손 사유), 고가 식기 = 비품(카테고리 기준 단가 이상이면 자산)
INSERT INTO catalog.division VALUES ('TABLEWARE', '식기·포장', 3) ON CONFLICT (code) DO NOTHING;
UPDATE catalog.division SET sort_order = 4 WHERE code = 'MANAGEMENT';

-- 카테고리 단위 '비품 기준 단가': 품목 기준 공급가가 이 금액 이상이면 비품(자산)으로 관리
ALTER TABLE catalog.category ADD COLUMN IF NOT EXISTS asset_threshold BIGINT CHECK (asset_threshold IS NULL OR asset_threshold > 0);

INSERT INTO catalog.category (code, name, division_code, is_asset, sort_order, asset_threshold) VALUES
 ('TABLEWARE',  '식기',     'TABLEWARE', FALSE, 150, 100000),
 ('DISPOSABLE', '일회용품', 'TABLEWARE', FALSE, 170, NULL)
ON CONFLICT (code) DO NOTHING;
UPDATE catalog.category SET division_code = 'TABLEWARE', name = '포장', sort_order = 160 WHERE code = 'PACKAGING';

-- 기존 '포장 용품'에 있던 컵·뚜껑·홀더(슬리브)는 일회용품으로 이동 (데모 품목이 있을 때만 해당)
UPDATE catalog.item SET category_id = (SELECT id FROM catalog.category WHERE code = 'DISPOSABLE'), updated_at = now()
 WHERE sku IN ('PK-CUP-H12', 'PK-CUP-I16', 'PK-LID-1K', 'PK-HLD-500');

INSERT INTO inventory.reason_code VALUES ('TABLEWARE_BROKEN', 'LOSS', '식기 파손', 'DISPOSAL', TRUE) ON CONFLICT (code) DO NOTHING;
