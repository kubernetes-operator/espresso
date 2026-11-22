package kr.espresso.core.hr;

import kr.espresso.common.*;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/core/payroll")
public class PayrollController {
    private final JdbcClient db;
    private final StoreScope scope;
    private final PayrollService svc;
    private final ApprovalService approvals;
    private final DocTrail trail;
    private final HrController hr;

    public PayrollController(JdbcClient db, StoreScope scope, PayrollService svc, ApprovalService approvals, DocTrail trail, HrController hr) {
        this.db = db; this.scope = scope; this.svc = svc; this.approvals = approvals; this.trail = trail; this.hr = hr;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam UUID storeId) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER);
        return db.sql("select * from hr.payroll_run where store_id = ? order by period desc").param(storeId).query().listOfRows();
    }

    /** 점주: 매장 설정이 허용일 때만 개인별, 아니면 총액만 */
    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        Map<String, Object> r = new LinkedHashMap<>(svc.load(id));
        UUID store = (UUID) r.get("store_id");
        scope.requireRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER);
        boolean detail = scope.hasRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN) || hr.payrollVisible(u, store);
        r.put("detailVisible", detail);
        r.put("payslips", detail ? db.sql("""
                select p.*, e.name, e.emp_no, c.employment_type, c.pay_type, c.base_wage,
                  (select jsonb_agg(jsonb_build_object('kind', i.kind, 'code', i.code, 'name', i.name, 'amount', i.amount, 'basis', i.basis) order by i.kind, i.code)
                   from hr.payslip_item i where i.payslip_id = p.id)::text items
                from hr.payslip p join hr.employee e on e.id = p.employee_id join hr.employment_contract c on c.id = p.contract_id
                where p.payroll_run_id = ? order by e.emp_no""").param(id).query().listOfRows() : List.of());
        r.put("approval", approvals.forDoc(PayrollService.DOC, id));
        r.put("history", trail.history(PayrollService.DOC, id));
        return r;
    }

    @PostMapping("/runs")
    public Map<String, Object> calc(@RequestBody Map<String, String> b) {
        return Map.of("id", svc.createOrRecalc(UUID.fromString(b.get("storeId")), b.get("period"), CurrentUser.get()));
    }

    @PostMapping("/runs/{id}/submit")
    public Map<String, Object> submit(@PathVariable UUID id) { svc.submit(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/runs/{id}/publish")
    public Map<String, Object> publish(@PathVariable UUID id) { return Map.of("published", svc.publish(id, CurrentUser.get())); }

    @PostMapping("/runs/{id}/paid")
    public Map<String, Object> paid(@PathVariable UUID id) { svc.markPaid(id, CurrentUser.get()); return Map.of("ok", true); }

    /** 명세서: 본인 / 인사관리자·총괄 / 열람 허용 점주 */
    @GetMapping("/payslips/{id}")
    public Map<String, Object> payslip(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var p = db.sql("""
                select p.*, e.name, e.emp_no, e.user_id, r.period, r.store_id, s.name store_name, c.employment_type, c.pay_type, c.base_wage
                from hr.payslip p join hr.employee e on e.id = p.employee_id join hr.payroll_run r on r.id = p.payroll_run_id
                join store.store s on s.id = r.store_id join hr.employment_contract c on c.id = p.contract_id where p.id = ?""").param(id).query().singleRow();
        UUID store = (UUID) p.get("store_id");
        boolean self = u.id().equals(p.get("user_id"));
        if (!self && !scope.hasRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN) && !hr.payrollVisible(u, store))
            throw ApiException.forbidden("이 급여명세서를 볼 권한이 없습니다.");
        if (self && p.get("published_at") == null) throw ApiException.forbidden("아직 발행되지 않은 명세서입니다.");
        if (self) db.sql("update hr.payslip set viewed_at = coalesce(viewed_at, now()) where id = ?").param(id).update();
        Map<String, Object> m = new LinkedHashMap<>(p);
        m.put("items", db.sql("select kind, code, name, amount, cast(basis as text) basis from hr.payslip_item where payslip_id = ? order by kind, code").param(id).query().listOfRows());
        return m;
    }

    /** 전체(합산) 화면: 매장별 급여 지급 총액 표 (상신 없음) */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String period) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = scope.allowed(u, List.of(Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER));
        Map<String, Object> out = new LinkedHashMap<>();
        if (st.isEmpty()) { out.put("rows", List.of()); out.put("periods", List.of()); return out; }
        out.put("periods", db.sql("select distinct period from hr.payroll_run where store_id in (:st) order by period desc").param("st", st).query(String.class).list());
        out.put("rows", db.sql("""
                select r.id, r.store_id, s.name store_name, s.site_type, r.period, r.status, r.headcount, r.total_gross, r.total_deduction, r.total_net,
                       r.total_employer_contrib, r.total_severance_accrual, r.confirmed_at, r.paid_at
                from hr.payroll_run r join store.store s on s.id = r.store_id
                where r.store_id in (:st) and (cast(:p as varchar) is null or r.period = cast(:p as varchar))
                order by r.period desc, case s.site_type when 'HQ' then 0 when 'STORE' then 1 else 2 end, s.code""")
                .param("st", st).param("p", period == null || period.isBlank() ? null : period).query().listOfRows());
        return out;
    }

    /** 전체(합산) 화면: 귀속월의 모든 사이트 직원별 급여 (총괄·인사 관리자·슈퍼바이저) */
    @GetMapping("/summary/employees")
    public List<Map<String, Object>> allEmployees(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HR_MGR, Roles.HQ_ADMIN);
        Set<UUID> st = scope.allowed(u, List.of(Roles.HR_MGR, Roles.HQ_ADMIN));
        if (st.isEmpty()) return List.of();
        return db.sql("""
                select p.id, s.name store_name, e.emp_no, e.name, e.job_title, c.employment_type, c.salary_grade, r.status run_status,
                       p.gross_pay, p.total_deduction, p.net_pay, p.employer_contrib, p.severance_accrual, p.worked_minutes
                from hr.payslip p join hr.payroll_run r on r.id = p.payroll_run_id join hr.employee e on e.id = p.employee_id
                join store.store s on s.id = r.store_id join hr.employment_contract c on c.id = p.contract_id
                where r.period = :p and r.store_id in (:st)
                order by case s.site_type when 'HQ' then 0 when 'STORE' then 1 else 2 end, s.code, e.emp_no""")
                .param("p", period).param("st", st).query().listOfRows();
    }

    /** 상여금·퇴직금 입력 목록: 그 달 재직(또는 그 달 퇴사) 직원 + 퇴직금 추정치 */
    @GetMapping("/extras")
    public List<Map<String, Object>> extras(@RequestParam UUID storeId, @RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, Roles.HR_MGR, Roles.HQ_ADMIN);
        var ym = PayrollService.ym(period);
        return db.sql("""
                select e.id employee_id, e.emp_no, e.name, e.hire_date, e.resign_date, e.status,
                  (select amount from hr.payroll_extra x where x.store_id = :s and x.period = :p and x.employee_id = e.id and x.code = 'BONUS') bonus,
                  (select amount from hr.payroll_extra x where x.store_id = :s and x.period = :p and x.employee_id = e.id and x.code = 'SEVERANCE') severance,
                  (select memo from hr.payroll_extra x where x.store_id = :s and x.period = :p and x.employee_id = e.id and x.code = 'SEVERANCE') severance_memo,
                  case when e.resign_date between :f and :t and e.resign_date - e.hire_date >= 365 then
                    -- 퇴직금 추정 = 1일 평균임금(최근 3개월 지급 ÷ 91일) × 30일 × 재직일수/365
                    round(coalesce((select sum(p.gross_pay) from hr.payslip p join hr.payroll_run r on r.id = p.payroll_run_id
                                    where p.employee_id = e.id and r.period < :p and r.period >= to_char(cast(:f as date) - interval '3 months', 'YYYYMM')), 0) / 91.0 * 30
                          * (e.resign_date - e.hire_date) / 365.0)
                  end suggested_severance
                from hr.employee e
                where e.store_id = :s and e.hire_date <= :t and (e.resign_date is null or e.resign_date >= :f)
                order by e.emp_no""")
                .param("s", storeId).param("p", period).param("f", ym.atDay(1)).param("t", ym.atEndOfMonth()).query().listOfRows();
    }

    public record ExtraReq(UUID storeId, String period, UUID employeeId, String code, Long amount, String memo) {}

    @PutMapping("/extras")
    public Map<String, Object> setExtra(@RequestBody ExtraReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), Roles.HR_MGR, Roles.HQ_ADMIN);
        if (!Set.of("BONUS", "SEVERANCE").contains(r.code())) throw ApiException.bad("항목은 상여금(BONUS) / 퇴직금(SEVERANCE) 입니다.");
        var st = db.sql("select status from hr.payroll_run where store_id = ? and period = ?").params(r.storeId(), r.period()).query(String.class).optional();
        if (st.isPresent() && List.of("REQUESTED", "CONFIRMED", "PAID").contains(st.get())) throw ApiException.conflict("결재 중이거나 확정된 급여는 바꿀 수 없습니다.");
        if (r.amount() == null || r.amount() <= 0)
            db.sql("delete from hr.payroll_extra where store_id = ? and period = ? and employee_id = ? and code = ?").params(r.storeId(), r.period(), r.employeeId(), r.code()).update();
        else
            db.sql("""
                    insert into hr.payroll_extra(store_id, period, employee_id, code, amount, memo, updated_by) values (?,?,?,?,?,?,?)
                    on conflict (store_id, period, employee_id, code) do update set amount = excluded.amount, memo = excluded.memo, updated_by = excluded.updated_by, updated_at = now()""")
                    .params(r.storeId(), r.period(), r.employeeId(), r.code(), r.amount(), r.memo(), u.id()).update();
        return Map.of("ok", true);
    }

    @GetMapping("/rates")
    public List<Map<String, Object>> rates(@RequestParam int year) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.HR_MGR);
        return db.sql("select * from hr.payroll_rate where year = ? order by code").param(year).query().listOfRows();
    }

    @PutMapping("/rates")
    public Map<String, Object> setRate(@RequestBody Map<String, Object> b) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("""
                insert into hr.payroll_rate(year, code, value, memo) values (?,?, cast(? as numeric), ?)
                on conflict (year, code) do update set value = excluded.value, memo = coalesce(excluded.memo, hr.payroll_rate.memo)""")
                .params(((Number) b.get("year")).intValue(), b.get("code"), b.get("value").toString(), b.get("memo")).update();
        return Map.of("ok", true);
    }
}
