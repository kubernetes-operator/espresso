package kr.espresso.core.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.common.*;
import kr.espresso.core.approval.ApprovalHandler;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

/** 급여 실행: 계산 → 상신(인적관리자 → 점주 → 총괄) → 확정 시 결산 이벤트 → 명세서 발행 */
@Service
public class PayrollService implements ApprovalHandler {
    public static final String DOC = "PAYROLL_RUN";
    private final JdbcClient db;
    private final StoreScope scope;
    private final PayrollCalculator calc;
    private final ApprovalService approvals;
    private final DocTrail trail;
    private final Outbox outbox;
    private final Notifier notifier;
    private final ObjectMapper om;

    public PayrollService(JdbcClient db, StoreScope scope, PayrollCalculator calc, ApprovalService approvals, DocTrail trail, Outbox outbox, Notifier notifier, ObjectMapper om) {
        this.db = db; this.scope = scope; this.calc = calc; this.approvals = approvals; this.trail = trail; this.outbox = outbox; this.notifier = notifier; this.om = om;
    }

    static String period(YearMonth ym) { return String.format("%d%02d", ym.getYear(), ym.getMonthValue()); }
    static YearMonth ym(String p) { return YearMonth.of(Integer.parseInt(p.substring(0, 4)), Integer.parseInt(p.substring(4))); }

    @Transactional
    public UUID createOrRecalc(UUID store, String period, CurrentUser u) {
        scope.requireRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN);
        var cur = db.sql("select id, status from hr.payroll_run where store_id = ? and period = ?").params(store, period).query().listOfRows();
        UUID id;
        if (cur.isEmpty()) {
            id = UUID.randomUUID();
            db.sql("insert into hr.payroll_run(id, store_id, period, created_by) values (?,?,?,?)").params(id, store, period, u.id()).update();
            trail.log(DOC, id, null, "DRAFT", u.id(), "급여 실행 생성");
        } else {
            id = (UUID) cur.get(0).get("id");
            if (!List.of("DRAFT", "CALCULATED", "REJECTED").contains((String) cur.get(0).get("status"))) throw ApiException.conflict("결재 중이거나 확정된 급여는 다시 계산할 수 없습니다.");
        }
        db.sql("delete from hr.payslip where payroll_run_id = ?").param(id).update();
        YearMonth ym = ym(period);
        Map<String, Object> settings = db.sql("select cast(settings as text) s from store.store where id = ?").param(store).query().singleRow();
        boolean fivePlus = !String.valueOf(settings.get("s")).contains("\"fivePlus\": false") && !String.valueOf(settings.get("s")).contains("\"fivePlus\":false");
        long tg = 0, td = 0, tn = 0, te = 0, ts = 0; int head = 0;
        for (var e : db.sql("""
                select c.*, c.id as contract_id from hr.employee e join lateral (select * from hr.employment_contract c where c.employee_id = e.id
                  and c.valid_from <= ? and (c.valid_to is null or c.valid_to >= ?) order by valid_from desc limit 1) c on true
                where e.store_id = ? and e.hire_date <= ? and (e.resign_date is null or e.resign_date >= ?)""")
                .params(ym.atEndOfMonth(), ym.atDay(1), store, ym.atEndOfMonth(), ym.atDay(1)).query().listOfRows()) {
            UUID emp = (UUID) e.get("employee_id");
            var work = db.sql("select * from hr.work_record where employee_id = ? and work_date between ? and ? and clock_out is not null")
                    .params(emp, ym.atDay(1), ym.atEndOfMonth()).query().listOfRows();
            if (work.isEmpty() && !"MONTHLY".equals(e.get("pay_type"))) continue;
            var r = calc.calc(e, work, ym, fivePlus);
            UUID pid = UUID.randomUUID();
            db.sql("insert into hr.payslip(id, payroll_run_id, employee_id, contract_id, gross_pay, taxable_pay, total_deduction, net_pay, employer_contrib, severance_accrual, worked_minutes) values (?,?,?,?,?,?,?,?,?,?,?)")
                    .params(pid, id, emp, e.get("contract_id"), r.gross(), r.taxable(), r.deduction(), r.net(), r.employer(), r.severance(), r.workedMinutes()).update();
            for (var it : r.items())
                db.sql("insert into hr.payslip_item(payslip_id, kind, code, name, amount, basis) values (?,?,?,?,?, cast(? as jsonb))")
                        .params(pid, it.kind(), it.code(), it.name(), it.amount(), json(it.basis())).update();
            tg += r.gross(); td += r.deduction(); tn += r.net(); te += r.employer(); ts += r.severance(); head++;
        }
        db.sql("""
                update hr.payroll_run set status = 'CALCULATED', headcount = ?, total_gross = ?, total_deduction = ?, total_net = ?,
                  total_employer_contrib = ?, total_severance_accrual = ?, calculated_at = now() where id = ?""")
                .params(head, tg, td, tn, te, ts, id).update();
        trail.log(DOC, id, null, "CALCULATED", u.id(), head + "명 계산 · 지급 " + tg + "원");
        return id;
    }

    String json(Object o) { try { return om.writeValueAsString(o); } catch (Exception e) { return "{}"; } }

    @Transactional
    public void submit(UUID id, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), Roles.HR_MGR, Roles.HQ_ADMIN);
        if (!"CALCULATED".equals(r.get("status"))) throw ApiException.conflict("계산된 급여만 상신할 수 있습니다.");
        if (((Number) r.get("headcount")).intValue() == 0) throw ApiException.bad("급여 대상자가 없습니다.");
        db.sql("update hr.payroll_run set status = 'REQUESTED', requested_by = ?, requested_at = now() where id = ?").params(u.id(), id).update();
        trail.log(DOC, id, "CALCULATED", "REQUESTED", u.id(), "상신");
        approvals.start(DOC, id, (UUID) r.get("store_id"), ((Number) r.get("total_gross")).longValue() + ((Number) r.get("total_employer_contrib")).longValue(), u);
    }

    public Map<String, Object> load(UUID id) {
        var r = db.sql("select * from hr.payroll_run where id = ?").param(id).query().listOfRows();
        if (r.isEmpty()) throw ApiException.notFound("급여 실행");
        return r.get(0);
    }

    @Override public String docType() { return DOC; }
    @Override public String docLabel() { return "급여 확정"; }

    @Override
    public Map<String, Object> summary(UUID docId) {
        var r = db.sql("select r.*, s.name store_name from hr.payroll_run r join store.store s on s.id = r.store_id where r.id = ?").param(docId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", "PR-" + r.get("period")); m.put("title", r.get("period") + " 급여 " + r.get("headcount") + "명 (총액)");
        m.put("storeId", r.get("store_id")); m.put("storeName", r.get("store_name"));
        m.put("amount", ((Number) r.get("total_gross")).longValue() + ((Number) r.get("total_employer_contrib")).longValue());
        m.put("status", r.get("status")); m.put("link", "/payroll/" + docId);
        return m;
    }

    @Override
    public void onApproved(UUID docId, UUID actor) {
        var r = load(docId);
        db.sql("update hr.payroll_run set status = 'CONFIRMED', confirmed_at = now() where id = ?").param(docId).update();
        trail.log(DOC, docId, "REQUESTED", "CONFIRMED", actor, "최종 승인 · 확정");
        Map<String, Long> by = new HashMap<>();
        for (var x : db.sql("""
                select case when c.employment_type in ('PART_TIME','FREELANCER') then 'PT' else 'REG' end cat, i.code, sum(i.amount) amt
                from hr.payslip p join hr.employment_contract c on c.id = p.contract_id join hr.payslip_item i on i.payslip_id = p.id
                where p.payroll_run_id = ? and i.kind = 'EARNING' group by 1, 2""").param(docId).query().listOfRows()) {
            String key = "BASE".equals(x.get("code")) || "MEAL".equals(x.get("code")) || "WEEKLY_HOLIDAY".equals(x.get("code")) ? x.get("cat").toString() : "ALLOW";
            by.merge(key, ((Number) x.get("amt")).longValue(), Long::sum);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("payrollRunId", docId); p.put("storeId", r.get("store_id")); p.put("period", r.get("period"));
        p.put("regularPay", by.getOrDefault("REG", 0L)); p.put("partTimePay", by.getOrDefault("PT", 0L)); p.put("allowances", by.getOrDefault("ALLOW", 0L));
        p.put("employerContrib", r.get("total_employer_contrib")); p.put("severanceAccrual", r.get("total_severance_accrual"));
        p.put("deductions", r.get("total_deduction")); p.put("netPay", r.get("total_net"));
        outbox.publish("hr", DOC, docId, "PayrollConfirmed", p);
    }

    @Override
    public void onRejected(UUID docId, UUID actor, String comment) {
        db.sql("update hr.payroll_run set status = 'REJECTED' where id = ?").param(docId).update();
        trail.log(DOC, docId, "REQUESTED", "REJECTED", actor, comment);
    }

    @Override
    public void onWithdrawn(UUID docId, UUID actor) {
        db.sql("update hr.payroll_run set status = 'CALCULATED', requested_at = null where id = ?").param(docId).update();
        trail.log(DOC, docId, "REQUESTED", "CALCULATED", actor, "회수");
    }

    /** 확정 후 명세서 발행 → 직원 앱 알림 */
    @Transactional
    public int publish(UUID id, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), Roles.HR_MGR, Roles.HQ_ADMIN);
        if (!List.of("CONFIRMED", "PAID").contains((String) r.get("status"))) throw ApiException.conflict("확정된 급여만 명세서를 발행할 수 있습니다.");
        int n = db.sql("update hr.payslip set published_at = now() where payroll_run_id = ? and published_at is null").param(id).update();
        var users = db.sql("select e.user_id from hr.payslip p join hr.employee e on e.id = p.employee_id where p.payroll_run_id = ? and e.user_id is not null").param(id).query(UUID.class).list();
        notifier.send(users, "PAYSLIP", r.get("period") + " 급여명세서", "급여명세서가 발행되었습니다", "/my", (UUID) r.get("store_id"));
        trail.log(DOC, id, (String) r.get("status"), (String) r.get("status"), u.id(), "명세서 발행 " + n + "건");
        return n;
    }

    @Transactional
    public void markPaid(UUID id, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), Roles.HR_MGR, Roles.HQ_ADMIN);
        if (!"CONFIRMED".equals(r.get("status"))) throw ApiException.conflict("확정된 급여만 지급 완료 처리합니다.");
        db.sql("update hr.payroll_run set status = 'PAID', paid_at = now() where id = ?").param(id).update();
        trail.log(DOC, id, "CONFIRMED", "PAID", u.id(), "지급 완료");
    }
}
