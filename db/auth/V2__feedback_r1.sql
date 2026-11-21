-- =====================================================================
-- 1차 사용자 점검 반영 (auth)
--  - 인적 관리자 → 인사 관리자
--  - 계정 VAT 표시 기본값 = 별도
--  - 초기 비밀번호 사용 여부 (첫 로그인 후 변경 안내)
-- =====================================================================
UPDATE auth.role SET name = '인사 관리자', description = '담당 매장 직원·근무·급여' WHERE code = 'HR_MGR';

UPDATE auth.app_user SET vat_display_override = 'EXCLUSIVE' WHERE vat_display_override IS NULL;
ALTER TABLE auth.app_user ALTER COLUMN vat_display_override SET DEFAULT 'EXCLUSIVE';

ALTER TABLE auth.app_user ADD COLUMN initial_password BOOLEAN NOT NULL DEFAULT FALSE;   -- 초기 비밀번호 그대로 사용 중
ALTER TABLE auth.app_user ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE auth.app_user ADD COLUMN suspended_by UUID;
