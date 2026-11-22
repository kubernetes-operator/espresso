package kr.espresso.core.report;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * 재고 소비 분석 (추정 지표): POS 연동 전이라 판매 수량 대신 "물류(입고)량·금액"과 "구분 판매 금액"의 비율로 본다.
 *  - 원두(kg)·우유(L)·시럽(L)  ↔ 음료 매출,  베이커리(kg, 총중량) ↔ 디저트 매출
 *  - 기준(basis): receipt = 물류 입고량 (기본, 실사가 없어도 계산됨) · usage = 재고 소진량 (실사의 판매 소진·차이 + 손실·결손 + 내부사용)
 *  - 지표: 기준량 1단위당 매출, 물류(원가) 비율 = 금액 ÷ 매출, 손실 비중 = 손실·결손 ÷ 기준량
 * 절대값이 아니라 같은 매장의 평소 수준·매장 간 비교로 해석한다 (재고 보유 변동, 입고 시점, 기록 누락에 따라 흔들림).
 */
@RestController
@RequestMapping("/api/core/reports/consumption")
public class ConsumptionController {
    static final List<String> ROLES = List.of(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.OWNER, Roles.STORE_MGR);
    /** 그룹 → [매출 구분, 표시 단위] */
    static final LinkedHashMap<String, String[]> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("BEAN", new String[]{"BEVERAGE", "kg"});
        GROUPS.put("MILK", new String[]{"BEVERAGE", "L"});
        GROUPS.put("SYRUP", new String[]{"BEVERAGE", "L"});
        GROUPS.put("BAKERY", new String[]{"DESSERT", "kg"});
    }
    private final JdbcClient db;
    private final StoreScope scope;

    public ConsumptionController(JdbcClient db, StoreScope scope) { this.db = db; this.scope = scope; }

    @GetMapping
    public Map<String, Object> report(@RequestParam(defaultValue = "month") String unit, @RequestParam(required = false) String to,
                                      @RequestParam(defaultValue = "12") int count, @RequestParam(required = false) UUID storeId,
                                      @RequestParam(defaultValue = "receipt") String basis) {
        CurrentUser u = CurrentUser.get();
        u.require(ROLES.toArray(String[]::new));
        boolean week = "week".equals(unit), usage = "usage".equals(basis);
        count = Math.max(1, Math.min(count, week ? 26 : 24));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate end = to == null || to.isBlank() ? today : to.length() == 7 ? YearMonth.parse(to).atEndOfMonth() : LocalDate.parse(to);
        LocalDate lastStart = week ? end.with(DayOfWeek.MONDAY) : end.withDayOfMonth(1);
        LocalDate first = week ? lastStart.minusWeeks(count - 1) : lastStart.minusMonths(count - 1);
        LocalDate until = week ? lastStart.plusWeeks(1) : lastStart.plusMonths(1);
        LocalDate curStart = week ? today.with(DayOfWeek.MONDAY) : today.withDayOfMonth(1);
        List<LocalDate> buckets = new ArrayList<>();
        for (LocalDate b = first; b.isBefore(until); b = week ? b.plusWeeks(1) : b.plusMonths(1)) buckets.add(b);

        Set<UUID> allowed = scope.allowed(u, ROLES);
        if (storeId != null) { scope.check(u, storeId); allowed = Set.of(storeId); }
        List<Map<String, Object>> stores = allowed.isEmpty() ? List.of() : db.sql(
                "select id, name, code from store.store where id in (:st) and site_type = 'STORE' order by code").param("st", allowed).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unit", week ? "week" : "month");
        out.put("basis", usage ? "usage" : "receipt");
        out.put("buckets", buckets.stream().map(LocalDate::toString).toList());
        out.put("current", curStart.toString());
        List<Map<String, Object>> groups = new ArrayList<>();
        GROUPS.forEach((g, v) -> groups.add(Map.of("key", g.toLowerCase(), "unit", v[1], "sales_division", v[0])));
        out.put("groups", groups);
        if (stores.isEmpty()) { out.put("stores", List.of()); out.put("total", List.of()); out.put("missing_weight", List.of()); return out; }
        Set<UUID> ids = new LinkedHashSet<>();
        for (var s : stores) ids.add((UUID) s.get("id"));
        String trunc = week ? "week" : "month";

        // 재고 원장 — 매장 × 기간 × 그룹: 입고(RECEIPT), 소진(판매 소진·차이), 손실·결손, 내부사용 (양은 g·ml → kg·L)
        Map<String, Map<String, Object>> cons = new HashMap<>();
        for (var r : db.sql("""
                select l.store_id, cast(date_trunc(:tr, l.occurred_at at time zone 'Asia/Seoul') as date) b, c.consumption_group g,
                  coalesce(sum(case when l.reason = 'RECEIPT' then l.qty_delta * i.net_weight_g end), 0) / 1000 receipt_qty,
                  coalesce(sum(case when l.reason = 'RECEIPT' then l.amount end), 0) receipt_cost,
                  coalesce(sum(case when l.reason = 'RECEIPT' and i.net_weight_g is null then l.amount end), 0) receipt_cost_nw,
                  coalesce(sum(case when l.reason in ('SALE','COUNT_ADJ') then -l.qty_delta * i.net_weight_g end), 0) / 1000 sale_qty,
                  coalesce(sum(case when l.reason in ('LOSS','SHORTAGE') then -l.qty_delta * i.net_weight_g end), 0) / 1000 loss_qty,
                  coalesce(sum(case when l.reason = 'INTERNAL_USE' then -l.qty_delta * i.net_weight_g end), 0) / 1000 internal_qty,
                  coalesce(-sum(case when l.reason <> 'RECEIPT' then l.amount end), 0) used_cost,
                  coalesce(-sum(case when l.reason <> 'RECEIPT' and i.net_weight_g is null then l.amount end), 0) used_cost_nw
                from inventory.stock_ledger l join catalog.item i on i.id = l.item_id join catalog.category c on c.id = i.category_id
                where l.store_id in (:st) and c.consumption_group is not null
                  and l.reason in ('RECEIPT','SALE','COUNT_ADJ','LOSS','SHORTAGE','INTERNAL_USE')
                  and l.occurred_at >= cast(:from as date) and l.occurred_at < cast(:until as date)
                group by 1, 2, 3""").param("tr", trunc).param("st", ids).param("from", first).param("until", until).query().listOfRows())
            cons.put(r.get("store_id") + "|" + r.get("b") + "|" + r.get("g"), r);
        Map<String, Long> sales = new HashMap<>();
        for (var r : db.sql("""
                select d.store_id, cast(date_trunc(:tr, d.sales_date) as date) b, x.division_code dv, sum(x.supply_amount) amt
                from sales.daily_sales d join sales.daily_sales_division x on x.daily_sales_id = d.id
                where d.store_id in (:st) and d.sales_date >= :from and d.sales_date < :until and x.division_code in ('BEVERAGE','DESSERT')
                group by 1, 2, 3""").param("tr", trunc).param("st", ids).param("from", first).param("until", until).query().listOfRows())
            sales.put(r.get("store_id") + "|" + r.get("b") + "|" + r.get("dv"), ((Number) r.get("amt")).longValue());

        String[] F = {"receipt_qty", "receipt_cost", "receipt_cost_nw", "sale_qty", "loss_qty", "internal_qty", "used_cost", "used_cost_nw"};
        List<Map<String, Object>> storeOut = new ArrayList<>();
        Map<String, double[]> tot = new HashMap<>();
        for (var s : stores) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LocalDate b : buckets) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bucket", b.toString());
                row.put("in_progress", !b.isBefore(curStart));
                for (var e : GROUPS.entrySet()) {
                    var c = cons.get(s.get("id") + "|" + b + "|" + e.getKey());
                    double[] v = new double[F.length + 1];
                    for (int i = 0; i < F.length; i++) v[i] = num(c, F[i]);
                    v[F.length] = sales.getOrDefault(s.get("id") + "|" + b + "|" + e.getValue()[0], 0L);
                    row.put(e.getKey().toLowerCase(), metrics(v, usage));
                    double[] t = tot.computeIfAbsent(b + "|" + e.getKey(), k -> new double[F.length + 1]);
                    for (int i = 0; i < v.length; i++) t[i] += v[i];
                }
                rows.add(row);
            }
            Map<String, Object> so = new LinkedHashMap<>(s);
            so.put("rows", rows);
            storeOut.add(so);
        }
        List<Map<String, Object>> total = new ArrayList<>();
        for (LocalDate b : buckets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.toString());
            row.put("in_progress", !b.isBefore(curStart));
            for (String g : GROUPS.keySet()) row.put(g.toLowerCase(), metrics(tot.getOrDefault(b + "|" + g, new double[F.length + 1]), usage));
            total.add(row);
        }
        out.put("stores", storeOut);
        out.put("total", total);
        out.put("missing_weight", db.sql("""
                select i.id, i.sku, i.name, i.unit, c.name category_name, c.consumption_group
                from inventory.stock_ledger l join catalog.item i on i.id = l.item_id join catalog.category c on c.id = i.category_id
                where l.store_id in (:st) and c.consumption_group is not null and i.net_weight_g is null
                  and l.reason in ('RECEIPT','SALE','COUNT_ADJ','LOSS','SHORTAGE','INTERNAL_USE') and l.occurred_at >= cast(:from as date) and l.occurred_at < cast(:until as date)
                group by i.id, i.sku, i.name, i.unit, c.name, c.consumption_group order by c.consumption_group, i.name""")
                .param("st", ids).param("from", first).param("until", until).query().listOfRows());
        return out;
    }

    static double num(Map<String, Object> m, String k) { return m != null && m.get(k) instanceof Number n ? n.doubleValue() : 0; }

    /** v = [입고량, 입고 금액, 중량 없는 입고 금액, 판매 소진량, 손실·결손량, 내부사용량, 소진 원가, 중량 없는 소진 원가, 구분 매출] */
    static Map<String, Object> metrics(double[] v, boolean usage) {
        double receipt = v[0], used = v[3] + v[4] + v[5], salesAmt = v[8];
        double qty = usage ? used : receipt, cost = usage ? v[6] : v[1], costNw = usage ? v[7] : v[2];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qty", r(qty, 2));                       // 기준량 (kg·L)
        m.put("receipt_qty", r(receipt, 2)); m.put("used_qty", r(used, 2));
        m.put("sale_qty", r(v[3], 2)); m.put("loss_qty", r(v[4], 2)); m.put("internal_qty", r(v[5], 2));
        m.put("cost", Math.round(cost)); m.put("sales", Math.round(salesAmt));
        m.put("per_unit", qty > 0.0001 && salesAmt > 0 ? Math.round(salesAmt / qty) : null);          // 기준량 1단위당 매출
        m.put("cost_per_unit", qty > 0.0001 ? Math.round((cost - costNw) / qty) : null);
        m.put("cost_ratio", salesAmt > 0 && cost > 0 ? r(cost / salesAmt * 100, 1) : null);         // 물류(원가) 비율 %
        m.put("loss_ratio", qty > 0.0001 ? r(v[4] / qty * 100, 1) : null);                          // 손실 비중 %
        return m;
    }

    static double r(double x, int d) { return BigDecimal.valueOf(x).setScale(d, RoundingMode.HALF_UP).doubleValue(); }
}
