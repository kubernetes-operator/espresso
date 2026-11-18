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

    /** 명세서: 본인 / 인적관리자·총괄 / 열람 허용 점주 */
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
