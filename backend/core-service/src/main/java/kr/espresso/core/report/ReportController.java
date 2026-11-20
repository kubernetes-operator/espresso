package kr.espresso.core.report;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/core")
public class ReportController {
    private final JdbcClient db;
    private final StoreScope scope;
    private final ReportService svc;

    public ReportController(JdbcClient db, StoreScope scope, ReportService svc) { this.db = db; this.scope = scope; this.svc = svc; }

    @GetMapping("/reports")
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = storeId != null ? Set.of(storeId) : scope.allowed(u);
        if (storeId != null) scope.check(u, storeId);
        if (st.isEmpty()) return List.of();
        return db.sql("""
                select r.id, r.store_id, s.name store_name, r.period_from, r.version, r.published_by, r.published_at, r.vat_basis
                from report.report_snapshot r join store.store s on s.id = r.store_id where r.store_id in (:st) and r.report_type = 'STORE_MONTHLY'
                order by r.period_from desc, s.code, r.version desc""").param("st", st).query().listOfRows();
    }

    /** 발행: 총괄 또는 해당 매장 점주 */
    @PostMapping("/reports")
    public Map<String, Object> publish(@RequestBody Map<String, String> b) {
        CurrentUser u = CurrentUser.get();
        UUID store = UUID.fromString(b.get("storeId"));
        scope.requireRoleOn(u, store, Roles.HQ_ADMIN, Roles.OWNER);
        String period = b.get("period");
        YearMonth ym = ReportService.ym(period);
        var data = svc.build(store, period);
        int ver = db.sql("select coalesce(max(version),0)+1 from report.report_snapshot where store_id = ? and period_from = ? and report_type = 'STORE_MONTHLY'")
                .params(store, ym.atDay(1)).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        db.sql("insert into report.report_snapshot(id, report_type, store_id, period_from, period_to, version, data, vat_basis, published_by) values (?, 'STORE_MONTHLY', ?,?,?,?, cast(? as jsonb), 'EXCLUSIVE', ?)")
                .params(id, store, ym.atDay(1), ym.atEndOfMonth(), ver, svc.json(data), u.id()).update();
        return Map.of("id", id, "version", ver);
    }

    @GetMapping("/reports/{id}")
    public Map<String, Object> view(@PathVariable UUID id, @RequestParam(defaultValue = "WEB") String format) {
        CurrentUser u = CurrentUser.get();
        var r = db.sql("select r.*, cast(r.data as text) data_text, s.name store_name, s.code store_code from report.report_snapshot r join store.store s on s.id = r.store_id where r.id = ?")
                .param(id).query().singleRow();
        UUID store = (UUID) r.get("store_id");
        scope.check(u, store);
        Set<String> vis = svc.visibleSections(u, store);
        Map<String, Object> data = svc.parse((String) r.get("data_text"));
        Map<String, Object> sections = new LinkedHashMap<>();
        for (String s : ReportService.SECTIONS) if (vis.contains(s) && data.containsKey(s)) sections.put(s, data.get(s));
        if (vis.contains("OWN_PAYSLIP")) {
            String period = r.get("period_from").toString().substring(0, 7).replace("-", "");
            sections.put("OWN_PAYSLIP", db.sql("""
                    select p.id, p.gross_pay, p.total_deduction, p.net_pay, p.worked_minutes from hr.payslip p join hr.employee e on e.id = p.employee_id
                    join hr.payroll_run pr on pr.id = p.payroll_run_id where e.user_id = ? and pr.store_id = ? and pr.period = ? and p.published_at is not null""")
                    .params(u.id(), store, period).query().listOfRows());
            sections.put("MY_REQUESTS", db.sql("""
                    select count(*) total, count(*) filter (where status in ('COMPLETED','CLOSED')) completed from logistics.request
                    where created_by = ? and store_id = ? and created_at::date between ? and ?""").params(u.id(), store, r.get("period_from"), r.get("period_to")).query().singleRow());
        }
        db.sql("insert into report.access_log(snapshot_id, viewer_id, role_code, format) values (?,?,?,?)")
                .params(id, u.id(), String.join(",", u.roles()), "PDF".equals(format) ? "PDF" : "WEB").update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("store_id", store); out.put("store_name", r.get("store_name")); out.put("period", r.get("period_from").toString().substring(0, 7));
        out.put("version", r.get("version")); out.put("published_at", r.get("published_at")); out.put("published_by", r.get("published_by"));
        out.put("visible", vis); out.put("sections", sections);
        return out;
    }

    @GetMapping("/reports/sections")
    public List<Map<String, Object>> matrix() {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        return db.sql("select role_code, section, visible from report.section_visibility order by role_code, section").query().listOfRows();
    }

    @PutMapping("/reports/sections")
    public Map<String, Object> setMatrix(@RequestBody Map<String, Object> b) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("update report.section_visibility set visible = ? where role_code = ? and section = ?")
                .params(Boolean.TRUE.equals(b.get("visible")), b.get("role"), b.get("section")).update();
        return Map.of("ok", true);
    }

    /** 요구 17: 매장 비교 — 물류·재고·인건비 지표 (손익 지표는 settlement /api/stl/compare) */
    @GetMapping("/compare")
    public List<Map<String, Object>> compare(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.HR_MGR, Roles.OWNER);
        YearMonth ym = ReportService.ym(period);
        Set<UUID> st = scope.allowed(u, List.of(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.HR_MGR, Roles.OWNER));
        if (st.isEmpty()) return List.of();
        boolean logi = u.has(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.OWNER), hr = u.has(Roles.HQ_ADMIN, Roles.HR_MGR, Roles.OWNER);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : db.sql("select s.id, s.name, s.code, r.name region_name from store.store s left join store.region r on r.id = s.region_id where s.id in (:st) and s.site_type = 'STORE' order by s.code").param("st", st).query().listOfRows()) {
            UUID id = (UUID) s.get("id");
            Map<String, Object> m = new LinkedHashMap<>(s);
            m.putAll(metrics(id, period, ym, logi, hr));
            out.add(m);
        }
        return out;
    }

    /** 비교 기준값 (점주용): 전 매장 물류·인건비 지표의 최댓값·최솟값·평균 — 매장 이름 없음 */
    @GetMapping("/compare/benchmark")
    public Map<String, Object> benchmark(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        YearMonth ym = ReportService.ym(period);
        Map<String, List<Double>> vals = new LinkedHashMap<>();
        int n = 0;
        for (UUID id : db.sql("select id from store.store where site_type = 'STORE' and status = 'OPEN'").query(UUID.class).list()) {
            n++;
            metrics(id, period, ym, true, true).forEach((k, v) -> { if (v instanceof Number x) vals.computeIfAbsent(k, z -> new ArrayList<>()).add(x.doubleValue()); });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        vals.forEach((k, v) -> out.put(k, Map.of("max", v.stream().mapToDouble(d -> d).max().orElse(0), "min", v.stream().mapToDouble(d -> d).min().orElse(0),
                "avg", Math.round(v.stream().mapToDouble(d -> d).average().orElse(0) * 10) / 10.0)));
        return Map.of("count", n, "metrics", out);
    }

    Map<String, Object> metrics(UUID id, String period, YearMonth ym, boolean logi, boolean hr) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (logi) {
                m.putAll(db.sql("""
                        select count(*) lr_count, coalesce(sum(total_amount) filter (where status in ('COMPLETED','CLOSED')),0) lr_amount,
                          round(avg(extract(epoch from completed_at - requested_at)/3600) filter (where completed_at is not null)::numeric,1) lead_hours
                        from logistics.request where store_id = ? and created_at::date between ? and ?""").params(id, ym.atDay(1), ym.atEndOfMonth()).query().singleRow());
                m.put("loss_amount", db.sql("select coalesce(sum(total_amount),0) from inventory.stock_adjustment where store_id = ? and status = 'APPROVED' and approved_at::date between ? and ?")
                        .params(id, ym.atDay(1), ym.atEndOfMonth()).query(Long.class).single());
                m.put("stock_value", db.sql("select coalesce(round(sum(qty*avg_cost)),0) from inventory.stock_balance where store_id = ?").param(id).query(Long.class).single());
            }
            if (hr) {
                var pr = db.sql("select headcount, total_gross, total_employer_contrib from hr.payroll_run where store_id = ? and period = ?").params(id, period).query().listOfRows();
                m.put("headcount", pr.isEmpty() ? null : pr.get(0).get("headcount"));
                m.put("payroll_gross", pr.isEmpty() ? null : pr.get(0).get("total_gross"));
                m.put("work_hours", db.sql("select round(coalesce(sum(regular_minutes + overtime_minutes),0) / 60.0, 1) from hr.work_record where store_id = ? and work_date between ? and ?")
                        .params(id, ym.atDay(1), ym.atEndOfMonth()).query(java.math.BigDecimal.class).single());
            }
            return m;
    }
}
