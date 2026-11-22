package kr.espresso.core.hr;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 급여 자동 계산 (요구 25, 설계 §8.3) — 요율은 hr.payroll_rate 연도별 테이블
 *  - 시급: 기본(정규 시간) + 주휴(주 15시간 이상 근무 주, 소정시간/40×8h) + 연장 1.5 + 야간 0.5 가산 (5인 이상)
 *  - 월급: 월급 + 연장·야간 가산(통상시급 = 월급/209)
 *  - 공제: 국민연금·건강·장기요양·고용 + 소득세(간이세액표, 미등록 시 추정)·지방소득세 / 사업소득 3.3%
 *  - 사업자 부담: 4대보험 사업자분 + 산재 / 퇴직급여 충당금 = 지급액/12 (주 15시간 이상)
 *  - 연봉 등급(salary_grade)이 있으면 월급 = 현재 연봉 테이블 금액 / 12
 *  - 상여금(BONUS): 과세 지급 / 퇴직금(SEVERANCE): 지급하되 4대보험·근로소득세 대상에서 제외 (퇴직소득세는 별도 신고)
 *  - 비과세: 식대(한도) + 기타 비과세 항목(contract.non_taxable_allowances.etc)
 */
@Component
public class PayrollCalculator {
    private final JdbcClient db;
    private final com.fasterxml.jackson.databind.ObjectMapper om;
    public PayrollCalculator(JdbcClient db, com.fasterxml.jackson.databind.ObjectMapper om) { this.db = db; this.om = om; }

    public record Item(String kind, String code, String name, long amount, Map<String, Object> basis) {}
    public record Result(long gross, long taxable, long deduction, long net, long employer, long severance, int workedMinutes,
                         List<Item> items, String category, boolean taxEstimated) {}

    Map<String, Object[]> rates(int year) {
        Map<String, Object[]> m = new HashMap<>();
        for (var r : db.sql("select code, value, upper_limit, lower_limit from hr.payroll_rate where year = (select max(year) from hr.payroll_rate where year <= ?)").param(year).query().listOfRows())
            m.put((String) r.get("code"), new Object[]{r.get("value"), r.get("upper_limit"), r.get("lower_limit")});
        return m;
    }

    static double v(Map<String, Object[]> r, String k, double def) { var x = r.get(k); return x == null ? def : ((BigDecimal) x[0]).doubleValue(); }
    static Long lim(Map<String, Object[]> r, String k, int i) { var x = r.get(k); return x == null || x[i] == null ? null : ((Number) x[i]).longValue(); }

    public Result calc(Map<String, Object> contract, List<Map<String, Object>> work, YearMonth ym, boolean fivePlus) {
        return calc(contract, work, ym, fivePlus, Map.of());
    }

    public Result calc(Map<String, Object> contract, List<Map<String, Object>> work, YearMonth ym, boolean fivePlus, Map<String, Long> extras) {
        var r = rates(ym.getYear());
        List<Item> items = new ArrayList<>();
        String type = (String) contract.get("employment_type");
        String payType = (String) contract.get("pay_type");
        long wage = ((Number) contract.get("base_wage")).longValue();
        String grade = (String) contract.get("salary_grade");
        if (grade != null) {
            Long annual = db.sql("select annual_salary from hr.salary_grade where code = ?").param(grade).query(Long.class).optional().orElse(null);
            if (annual != null) { wage = annual / 12; payType = "MONTHLY"; }
        }
        double weeklyHours = ((BigDecimal) contract.get("weekly_contract_hours")).doubleValue();
        int reg = 0, ot = 0, night = 0, hol = 0;
        Map<LocalDate, Integer> weekMin = new TreeMap<>();
        for (var w : work) {
            reg += ((Number) w.get("regular_minutes")).intValue(); ot += ((Number) w.get("overtime_minutes")).intValue();
            night += ((Number) w.get("night_minutes")).intValue(); hol += ((Number) w.get("holiday_minutes")).intValue();
            LocalDate d = ((java.sql.Date) w.get("work_date")).toLocalDate();
            weekMin.merge(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), ((Number) w.get("regular_minutes")).intValue(), Integer::sum);
        }
        double hourly = "HOURLY".equals(payType) ? wage : wage / 209.0;
        long minWage = (long) v(r, "MIN_WAGE", 0);
        long base;
        if ("HOURLY".equals(payType)) {
            base = Math.round(hourly * reg / 60.0);
            items.add(new Item("EARNING", "BASE", "기본급", base, Map.of("hours", reg / 60.0, "hourly", wage)));
            if (Boolean.TRUE.equals(contract.get("weekly_holiday_pay"))) {
                long hp = 0; int weeks = 0;
                for (var e : weekMin.entrySet()) {
                    double h = e.getValue() / 60.0;
                    // 주휴: 해당 주 근무 15시간 이상, 주 소정시간 비례 (월 경계 주는 그 달 근무일이 있는 주에 귀속)
                    if (h >= 15 && YearMonth.from(e.getKey().plusDays(6)).equals(ym) ) { hp += Math.round(Math.min(weeklyHours, 40) / 40.0 * 8 * hourly); weeks++; }
                }
                if (hp > 0) items.add(new Item("EARNING", "WEEKLY_HOLIDAY", "주휴수당", hp, Map.of("weeks", weeks)));
            }
        } else {
            base = wage;
            items.add(new Item("EARNING", "BASE", grade != null ? "기본급(연봉 " + grade + ")" : "기본급(월급)", base,
                    grade != null ? Map.of("monthly", wage, "grade", grade) : Map.of("monthly", wage)));
        }
        double otMult = fivePlus ? v(r, "OVERTIME_MULT", 1.5) : 1.0;
        if (ot > 0) items.add(new Item("EARNING", "OVERTIME", "연장근로수당", Math.round(hourly * otMult * ot / 60.0), Map.of("hours", ot / 60.0, "mult", otMult)));
        if (night > 0 && fivePlus) items.add(new Item("EARNING", "NIGHT", "야간근로가산", Math.round(hourly * v(r, "NIGHT_PREMIUM", 0.5) * night / 60.0), Map.of("hours", night / 60.0)));
        if (hol > 0) items.add(new Item("EARNING", "HOLIDAY", "휴일근로수당", Math.round(hourly * (fivePlus ? v(r, "HOLIDAY_MULT", 1.5) : 1.0) * hol / 60.0), Map.of("hours", hol / 60.0)));
        long bonus = extras.getOrDefault("BONUS", 0L), severance = extras.getOrDefault("SEVERANCE", 0L);
        if (bonus > 0) items.add(new Item("EARNING", "BONUS", "상여금", bonus, Map.of()));
        long meal = 0, otherNontax = 0;
        com.fasterxml.jackson.databind.JsonNode na = null;
        try { Object raw = contract.get("non_taxable_allowances"); if (raw != null) na = om.readTree(raw.toString()); } catch (Exception ignore) { }
        if (na != null) {
            meal = Math.min(na.path("meal").asLong(0), (long) v(r, "MEAL_NONTAX", 200000));
            if (meal > 0) items.add(new Item("EARNING", "MEAL", "식대(비과세)", meal, Map.of()));
            int k = 1;
            for (var x : na.path("etc")) {
                long amt = x.path("amount").asLong(0);
                if (amt <= 0) continue;
                items.add(new Item("EARNING", "NONTAX_" + k++, x.path("name").asText("기타") + "(비과세)", amt, Map.of()));
                otherNontax += amt;
            }
        }
        if (severance > 0) items.add(new Item("EARNING", "SEVERANCE", "퇴직금", severance, Map.of("note", "퇴직소득 — 4대보험·근로소득세 제외")));
        long gross = items.stream().filter(i -> i.kind().equals("EARNING")).mapToLong(Item::amount).sum();
        long taxable = gross - meal - otherNontax - severance;
        long emp = 0, ded = 0; boolean est = false;
        if ("FREELANCER".equals(type) || "BIZ_3_3".equals(contract.get("income_tax_type"))) {
            long t = (long) Math.floor((gross - severance) * v(r, "BIZ_TAX", 0.03) / 10) * 10;
            long lt = (long) Math.floor(t * v(r, "LOCAL_TAX", 0.1) / 10) * 10;
            items.add(new Item("DEDUCTION", "BIZ_TAX", "사업소득세 3%", t, Map.of()));
            items.add(new Item("DEDUCTION", "LOCAL_TAX", "지방소득세 0.3%", lt, Map.of()));
            ded = t + lt;
        } else {
            if (Boolean.TRUE.equals(contract.get("national_pension"))) {
                long b = taxable; Long up = lim(r, "NP_EMPLOYEE", 1), lo = lim(r, "NP_EMPLOYEE", 2);
                if (up != null) b = Math.min(b, up); if (lo != null) b = Math.max(b, lo);
                long e = trunc10(b * v(r, "NP_EMPLOYEE", 0)), er = trunc10(b * v(r, "NP_EMPLOYER", 0));
                items.add(new Item("DEDUCTION", "NP", "국민연금", e, Map.of("base", b))); items.add(new Item("EMPLOYER", "NP_ER", "국민연금(사업자)", er, Map.of()));
                ded += e; emp += er;
            }
            if (Boolean.TRUE.equals(contract.get("health_insurance"))) {
                long e = trunc10(taxable * v(r, "HI_EMPLOYEE", 0)), er = trunc10(taxable * v(r, "HI_EMPLOYER", 0));
                long ltc = trunc10(e * v(r, "LTC_RATE", 0)), ltcEr = trunc10(er * v(r, "LTC_RATE", 0));
                items.add(new Item("DEDUCTION", "HI", "건강보험", e, Map.of())); items.add(new Item("DEDUCTION", "LTC", "장기요양보험", ltc, Map.of()));
                items.add(new Item("EMPLOYER", "HI_ER", "건강보험(사업자)", er, Map.of())); items.add(new Item("EMPLOYER", "LTC_ER", "장기요양(사업자)", ltcEr, Map.of()));
                ded += e + ltc; emp += er + ltcEr;
            }
            if (Boolean.TRUE.equals(contract.get("employment_insurance"))) {
                long e = trunc10(taxable * v(r, "EI_EMPLOYEE", 0)), er = trunc10(taxable * v(r, "EI_EMPLOYER", 0));
                items.add(new Item("DEDUCTION", "EI", "고용보험", e, Map.of())); items.add(new Item("EMPLOYER", "EI_ER", "고용보험(사업자)", er, Map.of()));
                ded += e; emp += er;
            }
            int dep = ((Number) contract.get("tax_dependents")).intValue();
            var row = db.sql("select tax_amount from hr.income_tax_table where year = ? and ? >= salary_from and ? < salary_to and dependents = ?")
                    .params(ym.getYear(), taxable, taxable, Math.min(dep, 11)).query(Long.class).optional();
            long it = row.orElseGet(() -> estimateTax(taxable, dep));
            est = row.isEmpty();
            long lt = trunc10(it * v(r, "LOCAL_TAX", 0.1));
            items.add(new Item("DEDUCTION", "INCOME_TAX", est ? "소득세(간이 추정)" : "소득세", it, Map.of("estimated", est)));
            items.add(new Item("DEDUCTION", "LOCAL_TAX", "지방소득세", lt, Map.of()));
            ded += it + lt;
        }
        long ia = trunc10((gross - severance) * v(r, "IA_EMPLOYER", 0));
        items.add(new Item("EMPLOYER", "IA_ER", "산재보험(사업자)", ia, Map.of()));
        emp += ia;
        long sev = weeklyHours >= 15 && !"FREELANCER".equals(type) ? (gross - severance) / 12 : 0;
        if ("HOURLY".equals(payType) && wage < minWage) items.add(new Item("EMPLOYER", "WARN_MIN_WAGE", "최저임금 미달 경고", 0, Map.of("minWage", minWage)));
        String category = "PART_TIME".equals(type) || "FREELANCER".equals(type) ? "PART_TIME" : "REGULAR";
        return new Result(gross, taxable, ded, gross - ded, emp, sev, reg + ot, items, category, est);
    }

    static long trunc10(double v) { return (long) Math.floor(v / 10) * 10; }

    /** 간이세액표 미등록 시 추정 (월 과세급여, 부양가족). 운영 전 국세청 간이세액표를 hr.income_tax_table 에 등록 */
    static long estimateTax(long t, int dep) {
        double x = t - 1_060_000;
        if (x <= 0) return 0;
        double tax = Math.min(x, 1_400_000) * 0.06 + Math.max(0, Math.min(x, 5_000_000) - 1_400_000) * 0.15 + Math.max(0, x - 5_000_000) * 0.24;
        tax = tax * 0.55 - Math.max(dep - 1, 0) * 12_500;
        return Math.max(0, (long) Math.floor(tax / 10) * 10);
    }
}
