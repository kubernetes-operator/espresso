-- 2026 급여 요율 (데모 값 — 운영 전 고용노동부·공단·국세청 고시값으로 반드시 확인·갱신)
INSERT INTO hr.payroll_rate(year, code, value, upper_limit, lower_limit, memo) VALUES
 (2026,'MIN_WAGE',10320,NULL,NULL,'최저시급 (확인 필요)'),
 (2026,'NP_EMPLOYEE',0.0475,6370000,400000,'국민연금 근로자 (기준소득월액 상·하한, 확인 필요)'),
 (2026,'NP_EMPLOYER',0.0475,6370000,400000,'국민연금 사업자'),
 (2026,'HI_EMPLOYEE',0.03595,NULL,NULL,'건강보험 근로자 (확인 필요)'),
 (2026,'HI_EMPLOYER',0.03595,NULL,NULL,'건강보험 사업자'),
 (2026,'LTC_RATE',0.1314,NULL,NULL,'장기요양 = 건강보험료 × 비율 (확인 필요)'),
 (2026,'EI_EMPLOYEE',0.009,NULL,NULL,'고용보험 근로자'),
 (2026,'EI_EMPLOYER',0.0115,NULL,NULL,'고용보험 사업자 (실업급여+고용안정, 150인 미만)'),
 (2026,'IA_EMPLOYER',0.007,NULL,NULL,'산재보험 (업종별 상이, 확인 필요)'),
 (2026,'MEAL_NONTAX',200000,NULL,NULL,'식대 비과세 한도'),
 (2026,'OVERTIME_MULT',1.5,NULL,NULL,'연장근로 가산 (5인 이상)'),
 (2026,'NIGHT_PREMIUM',0.5,NULL,NULL,'야간(22~06시) 가산 (5인 이상)'),
 (2026,'HOLIDAY_MULT',1.5,NULL,NULL,'휴일근로 가산 (5인 이상)'),
 (2026,'BIZ_TAX',0.03,NULL,NULL,'사업소득 원천징수'),
 (2026,'LOCAL_TAX',0.1,NULL,NULL,'지방소득세 = 소득세 × 10%')
ON CONFLICT DO NOTHING;
