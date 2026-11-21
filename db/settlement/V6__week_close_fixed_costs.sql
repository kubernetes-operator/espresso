-- 3차 점검 반영: 주간 결산에 고정비(월세·관리비·감가상각) 일할 배분 → 기존 자동 주 마감 스냅샷을 다시 만들도록 비움
DELETE FROM stl.week_close WHERE auto;
