package kr.espresso.settlement.close;

import kr.espresso.common.*;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/stl")
public class SettlementController {
    private final SettlementService svc;
    private final CoreClient core;
    private final JdbcClient db;

    public SettlementController(SettlementService svc, CoreClient core, JdbcClient db) { this.svc = svc; this.core = core; this.db = db; }

    /** 월 결산 (매장 1곳 또는 볼 수 있는 매장 합산). 매장관리자는 결산액·로열티 제외 요약 */
    @GetMapping("/settlement")
    public Map<String, Object> summary(@RequestParam(required = false) UUID storeId, @RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR);
        List<UUID> stores = storeId != null ? List.of(storeId) : new ArrayList<>(core.myStoreIds());
        if (storeId != null) core.check(storeId);
        List<Map<String, Object>> per = new ArrayList<>();
        for (UUID s : stores) {
            Map<String, Object> m = svc.summary(s, period);
            boolean full = u.isHqAdmin() || u.assignments().stream().anyMatch(a -> Roles.OWNER.equals(a.role()) && s.equals(a.scopeId()));
            if (!full) { m.put("royalty", null); m.put("settlement_amount", null); m.put("non_operating", null); m.put("limited", true); }
            per.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("stores", per);
        if (per.size() > 1) out.put("total", total(per));
        return out;
    }

    static Map<String, Object> total(List<Map<String, Object>> per) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("net_sales", "cogs", "gross_profit", "labor", "rent", "utility", "internal_use", "operating_exp", "stock_loss", "depreciation", "operating_profit", "royalty", "non_operating", "settlement_amount", "purchases", "closing_stock", "opening_stock")) {
            long sum = 0; boolean any = false;
            for (var m : per) if (m.get(k) instanceof Number n) { sum += n.longValue(); any = true; }
            t.put(k, any ? sum : null);
        }
        long sales = (Long) t.get("net_sales");
        t.put("ratios", SettlementService.ratios(sales, (Long) t.get("cogs"), (Long) t.get("labor"), (Long) t.get("rent"), (Long) t.get("stock_loss"),
                t.get("settlement_amount") == null ? 0 : (Long) t.get("settlement_amount")));
        return t;
    }

    /** 결산 상세: 계정과목별 합계 */
    @GetMapping("/settlement/accounts")
    public List<Map<String, Object>> accounts(@RequestParam UUID storeId, @RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        core.requireOn(u, storeId, Roles.OWNER);
        return db.sql("""
                select a.code, a.name, a.type, a.pnl_section, sum(l.debit) debit, sum(l.credit) credit
                from stl.journal_line l join stl.account_subject a on a.code = l.account_code
                where l.store_id = ? and l.period = ? group by a.code, a.name, a.type, a.pnl_section, a.sort_order order by a.sort_order""")
                .params(storeId, period).query().listOfRows();
    }

    @GetMapping("/journal")
    public List<Map<String, Object>> journal(@RequestParam UUID storeId, @RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        core.requireOn(u, storeId, Roles.OWNER);
        return db.sql("""
                select e.id, e.entry_date, e.source_type, e.description, e.is_adjustment, l.account_code, a.name account_name, l.debit, l.credit, l.division_code
                from stl.journal_entry e join stl.journal_line l on l.entry_id = e.id and l.entry_date = e.entry_date
                join stl.account_subject a on a.code = l.account_code
                where e.store_id = ? and e.period = ? order by e.entry_date, e.created_at, l.id""").params(storeId, period).query().listOfRows();
    }

    @PostMapping("/settlement/close")
    public Map<String, Object> close(@RequestBody Map<String, String> b) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        return svc.close(UUID.fromString(b.get("storeId")), b.get("period"), u.id());
    }

    @PostMapping("/settlement/reopen")
    public Map<String, Object> reopen(@RequestBody Map<String, String> b) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        svc.reopen(UUID.fromString(b.get("storeId")), b.get("period"), u.id());
        return Map.of("ok", true);
    }

    @GetMapping("/periods")
    public List<Map<String, Object>> periods(@RequestParam UUID storeId) {
        core.check(storeId);
        return db.sql("select * from stl.period_close where store_id = ? order by period desc").param(storeId).query().listOfRows();
    }

    // ---------------- 로열티 정책 (총괄) ----------------
    @GetMapping("/royalty-policies")
    public List<Map<String, Object>> policies(@RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        Set<UUID> st = storeId != null ? Set.of(storeId) : core.myStoreIds();
        if (st.isEmpty()) return List.of();
        return db.sql("select *, cast(tiers as text) tiers_text from stl.royalty_policy where store_id in (:st) order by store_id, valid_from desc").param("st", st).query().listOfRows();
    }

    public record PolicyReq(UUID storeId, String type, String base, BigDecimal rate, Long fixedAmount, Long minAmount, Long maxAmount, String tiers, String validFrom) {}

    @PostMapping("/royalty-policies")
    public Map<String, Object> setPolicy(@RequestBody PolicyReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (!Set.of("RATE", "FIXED", "RATE_PLUS_FIXED", "TIERED", "NONE").contains(r.type())) throw ApiException.bad("로열티 유형을 확인하세요.");
        if (r.rate() != null && (r.rate().signum() < 0 || r.rate().compareTo(BigDecimal.ONE) >= 0)) throw ApiException.bad("요율은 0~1 (예: 0.03 = 3%) 입니다.");
        LocalDate from = r.validFrom() == null ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(r.validFrom());
        db.sql("update stl.royalty_policy set valid_to = ? where store_id = ? and (valid_to is null or valid_to >= ?) and valid_from < ?").params(from.minusDays(1), r.storeId(), from, from).update();
        db.sql("delete from stl.royalty_policy where store_id = ? and valid_from >= ?").params(r.storeId(), from).update();
        UUID id = UUID.randomUUID();
        db.sql("insert into stl.royalty_policy(id, store_id, type, base, rate, fixed_amount, min_amount, max_amount, tiers, valid_from, created_by) values (?,?,?,?,?,?,?,?, cast(? as jsonb), ?, ?)")
                .params(id, r.storeId(), r.type(), Objects.requireNonNullElse(r.base(), "NET_SALES_EXCL_VAT"), r.rate(), r.fixedAmount(), r.minAmount(), r.maxAmount(),
                        r.tiers() == null || r.tiers().isBlank() ? null : r.tiers(), from, u.id()).update();
        db.sql("insert into stl.audit_log(actor_id, action, target_type, target_id, after_data) values (?, 'ROYALTY_CHANGE', 'STORE', ?, cast(? as jsonb))")
                .params(u.id(), r.storeId(), "{\"type\":\"" + r.type() + "\",\"base\":\"" + r.base() + "\"}").update();
        return Map.of("id", id);
    }
}
