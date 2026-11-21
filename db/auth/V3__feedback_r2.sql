-- 2차 사용자 점검 반영 (auth)
-- 슈퍼바이저 활성화: 총괄 바로 아래 등급, 물류·인사 관리자는 슈퍼바이저 아래
-- (권한: 총괄·슈퍼바이저는 물류 관리자·인사 관리자 권한을 함께 가짐 — CurrentUser 에서 확장)
UPDATE auth.role SET is_active = TRUE, rank = 1, parent_code = 'HQ_ADMIN',
       description = '총괄 아래 · 담당 권역의 물류·인사 관리 권한 포함' WHERE code = 'SUPERVISOR';
UPDATE auth.role SET rank = 2, parent_code = 'SUPERVISOR' WHERE code IN ('LOGISTICS_MGR','HR_MGR');
UPDATE auth.role SET description = '최상위, 전 매장 · 물류·인사 관리 권한 포함' WHERE code = 'HQ_ADMIN';
