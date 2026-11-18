package kr.espresso.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.YearMonth;
import java.util.*;

/** 1장 리포트 (요구 4·5): 발행 = 불변 스냅샷, 열람 = 등급별 섹션 필터 */
@Service
public class ReportService {
    public static final List<String> SECTIONS = List.of("KPI", "PNL", "DIVISION", "LOGISTICS", "STOCK", "LABOR_TOTAL", "LABOR_DETAIL", "RENT_OPEX", "ROYALTY", "SETTLEMENT", "APPROVALS", "OWN_PAYSLIP");
    private final JdbcClient db;
    private final StoreScope scope;
    private final RestClient stl;
    private final ObjectMapper om;

    public ReportService(JdbcClient db, StoreScope scope, @Value("${app.settlement-url}") String url, ObjectMapper om) {
        this.db = db; this.scope = scope; this.stl = RestClient.create(url); this.om = om;
    }

    static YearMonth ym(String p) { return YearMonth.of(Integer.parseInt(p.substring(0, 4)), Integer.parseInt(p.substring(4))); }

    public Map<String, Object> build(UUID store, String period) {
        YearMonth ym = ym(period);
        var from = ym.atDay(1); var to = ym.atEndOfMonth();
        Map<String, Object> s = stl.get().uri("/internal/settlement/summary?storeId={s}&period={p}", store, period).retrieve().body(new ParameterizedTypeReference<>() {});
        if (s == null) s = Map.of();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("KPI", pick(s, "net_sales", "gross_profit", "operating_profit", "ratios", "closed"));
        d.put("PNL", pick(s, "net_sales", "cogs", "opening_stock", "purchases", "closing_stock", "gross_profit", "labor", "rent", "utility", "internal_use", "operating_exp", "stock_loss", "depreciation", "operating_profit"));
        d.put("DIVISION", s.getOrDefault("by_division", Map.of()));
        d.put("LOGISTICS", db.sql("""
                select count(*) total, count(*) filter (where status = 'COMPLETED') completed,
                  count(*) filter (where status not in ('COMPLETED','CANCELLED','REJECTED','DRAFT')) open,
                  round(avg(extract(epoch from completed_at - requested_at) / 3600) filter (where completed_at is not null)::numeric, 1) lead_hours,
                  coalesce(sum(total_amount) filter (where status = 'COMPLETED'), 0) completed_amount,
                  coalesce((select sum(l.total_amount) from logistics.request_line l join logistics.request r2 on r2.id = l.request_id
                    where r2.store_id = :s and r2.status = 'COMPLETED' and l.source_type = 'EXTERNAL' and r2.completed_at::date between :f and :t), 0) external_amount
                from logistics.request where store_id = :s and created_at::date between :f and :t""")
                .param("s", store).param("f", from).param("t", to).query().singleRow());
        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("closing_stock", s.get("closing_stock"));
        stock.put("by_type", db.sql("""
                select adj_type, count(*) cnt, sum(total_amount) amount from inventory.stock_adjustment
                where store_id = ? and status = 'APPROVED' and approved_at::date between ? and ? group by adj_type""").params(store, from, to).query().listOfRows());
        stock.put("top", db.sql("""
                select i.name, rc.name reason, sum(l.qty) qty, sum(l.amount) amount from inventory.stock_adjustment a
                join inventory.stock_adjustment_line l on l.adjustment_id = a.id join catalog.item i on i.id = l.item_id join inventory.reason_code rc on rc.code = l.reason_code
                where a.store_id = ? and a.status = 'APPROVED' and a.approved_at::date between ? and ? group by i.name, rc.name order by sum(l.amount) desc limit 5""")
                .params(store, from, to).query().listOfRows());
        d.put("STOCK", stock);
        var run = db.sql("select * from hr.payroll_run where store_id = ? and period = ?").params(store, period).query().listOfRows();
        Map<String, Object> labor = new LinkedHashMap<>();
        labor.put("ledger_labor", s.get("labor"));
        if (!run.isEmpty()) {
            var r = run.get(0);
            labor.put("status", r.get("status")); labor.put("headcount", r.get("headcount")); labor.put("gross", r.get("total_gross"));
            labor.put("employer", r.get("total_employer_contrib")); labor.put("severance", r.get("total_severance_accrual"));
            d.put("LABOR_DETAIL", db.sql("""
                    select e.name, c.employment_type, p.worked_minutes, p.gross_pay, p.total_deduction, p.net_pay from hr.payslip p
                    join hr.employee e on e.id = p.employee_id join hr.employment_contract c on c.id = p.contract_id where p.payroll_run_id = ? order by e.emp_no""")
                    .param(r.get("id")).query().listOfRows());
        } else d.put("LABOR_DETAIL", List.of());
        d.put("LABOR_TOTAL", labor);
        d.put("RENT_OPEX", pick(s, "rent", "utility", "internal_use", "operating_exp", "depreciation"));
        d.put("ROYALTY", pick(s, "royalty"));
        d.put("SETTLEMENT", pick(s, "operating_profit", "royalty", "non_operating", "settlement_amount", "closed"));
        d.put("APPROVALS", db.sql("""
                select count(*) filter (where status = 'IN_PROGRESS') in_progress, count(*) filter (where status = 'REJECTED' and completed_at::date between ? and ?) rejected,
                  count(*) filter (where status = 'APPROVED' and completed_at::date between ? and ?) approved
                from approval.request where store_id = ?""").params(from, to, from, to, store).query().singleRow());
        return d;
    }

    static Map<String, Object> pick(Map<String, Object> m, String... keys) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : keys) o.put(k, m.get(k));
        return o;
    }

    /** 보는 사람의 섹션 (여러 등급이면 합집합). 개인별 인건비는 매장 설정까지 확인 */
    public Set<String> visibleSections(CurrentUser u, UUID store) {
        Set<String> roles = new HashSet<>();
        for (String r : List.of(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.HR_MGR, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF))
            if (scope.hasRoleOn(u, store, r)) roles.add(r);
        if (roles.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>(db.sql("select distinct section from report.section_visibility where visible and role_code in (:r)").param("r", roles).query(String.class).list());
        if (!roles.contains(Roles.HQ_ADMIN) && !roles.contains(Roles.HR_MGR)) {
            var st = db.sql("select owner_payroll_detail_visible o, manager_payroll_detail_visible m from store.store where id = ?").param(store).query().singleRow();
            boolean ok = (roles.contains(Roles.OWNER) && Boolean.TRUE.equals(st.get("o"))) || (roles.contains(Roles.STORE_MGR) && Boolean.TRUE.equals(st.get("m")));
            if (!ok) out.remove("LABOR_DETAIL");
        }
        return out;
    }

    public String json(Object o) { try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    public Map<String, Object> parse(String s) { try { return om.readValue(s, Map.class); } catch (Exception e) { return Map.of(); } }
}
