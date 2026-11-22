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
 * 재고 소비 분석: 기간(월·주)별 소비된 재고(판매 소진·실사 차이·손실·결손·내부사용)를 품목 단위당 중량으로 kg 환산해
 * 같은 기간 구분 매출과 비교.
 *  - 원두(BEAN): 음료 매출 ÷ 원두 소비 kg, 원두 원가율 = 원두 소비 원가 ÷ 음료 매출
 *  - 베이커리(BAKERY): 디저트 매출 ÷ 베이커리 소비 kg, 원가율
 *  - 손실 비중 = (손실 + 결손) kg ÷ 소비 kg
 * 다음 단계(수율): 판매 제품별 레시피 기준 이론 소비량과 실제 소비량 비교.
 */
@RestController
@RequestMapping("/api/core/reports/consumption")
public class ConsumptionController {
    static final List<String> ROLES = List.of(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.OWNER, Roles.STORE_MGR);
    static final Map<String, String> SALES_DIV = Map.of("BEAN", "BEVERAGE", "BAKERY", "DESSERT");
    private final JdbcClient db;
    private final StoreScope scope;

    public ConsumptionController(JdbcClient db, StoreScope scope) { this.db = db; this.scope = scope; }

    /**
     * @param unit  month | week
     * @param to    마지막 기간이 포함된 날짜 (YYYY-MM-DD 또는 YYYY-MM)
     * @param count 기간 수 (최대 24)
     */
    @GetMapping
    public Map<String, Object> report(@RequestParam(defaultValue = "month") String unit, @RequestParam(required = false) String to,
                                      @RequestParam(defaultValue = "12") int count, @RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        u.require(ROLES.toArray(String[]::new));
        boolean week = "week".equals(unit);
        count = Math.max(1, Math.min(count, week ? 26 : 24));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate end = to == null || to.isBlank() ? today : to.length() == 7 ? YearMonth.parse(to).atEndOfMonth() : LocalDate.parse(to);
        LocalDate lastStart = week ? end.with(DayOfWeek.MONDAY) : end.withDayOfMonth(1);
        LocalDate first = week ? lastStart.minusWeeks(count - 1) : lastStart.minusMonths(count - 1);
        LocalDate until = week ? lastStart.plusWeeks(1) : lastStart.plusMonths(1);
        List<LocalDate> buckets = new ArrayList<>();
        for (LocalDate b = first; b.isBefore(until); b = week ? b.plusWeeks(1) : b.plusMonths(1)) buckets.add(b);

        Set<UUID> allowed = scope.allowed(u, ROLES);
        if (storeId != null) { scope.check(u, storeId); allowed = Set.of(storeId); }
        List<Map<String, Object>> stores = allowed.isEmpty() ? List.of() : db.sql(
                "select id, name, code from store.store where id in (:st) and site_type = 'STORE' order by code").param("st", allowed).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unit", week ? "week" : "month");
        out.put("buckets", buckets.stream().map(LocalDate::toString).toList());
        if (stores.isEmpty()) { out.put("stores", List.of()); out.put("total", List.of()); out.put("missing_weight", List.of()); return out; }
        Set<UUID> ids = new LinkedHashSet<>();
        for (var s : stores) ids.add((UUID) s.get("id"));
        String trunc = week ? "week" : "month";

        // 소비 (중량·원가) — 매장 × 기간 × 그룹
        Map<String, Map<String, Object>> cons = new HashMap<>();
        for (var r : db.sql("""
                select l.store_id, cast(date_trunc(:tr, l.occurred_at at time zone 'Asia/Seoul') as date) b, c.consumption_group g,
                  coalesce(sum(case when l.reason in ('SALE','COUNT_ADJ') then -l.qty_delta * i.net_weight_g end), 0) / 1000 sale_kg,
                  coalesce(sum(case when l.reason in ('LOSS','SHORTAGE') then -l.qty_delta * i.net_weight_g end), 0) / 1000 loss_kg,
                  coalesce(sum(case when l.reason = 'INTERNAL_USE' then -l.qty_delta * i.net_weight_g end), 0) / 1000 internal_kg,
                  -sum(l.amount) cost,
                  -sum(case when i.net_weight_g is null then l.amount else 0 end) cost_no_weight
                from inventory.stock_ledger l join catalog.item i on i.id = l.item_id join catalog.category c on c.id = i.category_id
                where l.store_id in (:st) and c.consumption_group is not null
                  and l.reason in ('SALE','COUNT_ADJ','LOSS','SHORTAGE','INTERNAL_USE')
                  and l.occurred_at >= cast(:from as date) and l.occurred_at < cast(:until as date)
                group by 1, 2, 3""").param("tr", trunc).param("st", ids).param("from", first).param("until", until).query().listOfRows())
            cons.put(r.get("store_id") + "|" + r.get("b") + "|" + r.get("g"), r);
        // 구분 매출 — 매장 × 기간 × 구분
        Map<String, Long> sales = new HashMap<>();
        for (var r : db.sql("""
                select d.store_id, cast(date_trunc(:tr, d.sales_date) as date) b, x.division_code dv, sum(x.supply_amount) amt
                from sales.daily_sales d join sales.daily_sales_division x on x.daily_sales_id = d.id
                where d.store_id in (:st) and d.sales_date >= :from and d.sales_date < :until and x.division_code in ('BEVERAGE','DESSERT')
                group by 1, 2, 3""").param("tr", trunc).param("st", ids).param("from", first).param("until", until).query().listOfRows())
            sales.put(r.get("store_id") + "|" + r.get("b") + "|" + r.get("dv"), ((Number) r.get("amt")).longValue());

        List<Map<String, Object>> storeOut = new ArrayList<>();
        Map<LocalDate, Map<String, double[]>> tot = new LinkedHashMap<>();   // 기간 → 그룹 → [sale, loss, internal, cost, costNoW, sales]
        for (var s : stores) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LocalDate b : buckets) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bucket", b.toString());
                for (String g : List.of("BEAN", "BAKERY")) {
                    var c = cons.get(s.get("id") + "|" + b + "|" + g);
                    double[] v = {num(c, "sale_kg"), num(c, "loss_kg"), num(c, "internal_kg"), num(c, "cost"), num(c, "cost_no_weight"),
                            sales.getOrDefault(s.get("id") + "|" + b + "|" + SALES_DIV.get(g), 0L)};
                    row.put(g.toLowerCase(), metrics(v));
                    double[] t = tot.computeIfAbsent(b, k -> new HashMap<>()).computeIfAbsent(g, k -> new double[6]);
                    for (int i = 0; i < 6; i++) t[i] += v[i];
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
            for (String g : List.of("BEAN", "BAKERY")) row.put(g.toLowerCase(), metrics(tot.getOrDefault(b, Map.of()).getOrDefault(g, new double[6])));
            total.add(row);
        }
        out.put("stores", storeOut);
        out.put("total", total);
        // 중량이 없어 kg 에 빠진 품목 (소비가 있었던 것만)
        out.put("missing_weight", db.sql("""
                select i.id, i.sku, i.name, i.unit, c.name category_name, c.consumption_group, -sum(l.amount) cost
                from inventory.stock_ledger l join catalog.item i on i.id = l.item_id join catalog.category c on c.id = i.category_id
                where l.store_id in (:st) and c.consumption_group is not null and i.net_weight_g is null
                  and l.reason in ('SALE','COUNT_ADJ','LOSS','SHORTAGE','INTERNAL_USE') and l.occurred_at >= cast(:from as date) and l.occurred_at < cast(:until as date)
                group by i.id, i.sku, i.name, i.unit, c.name, c.consumption_group order by c.consumption_group, i.name""")
                .param("st", ids).param("from", first).param("until", until).query().listOfRows());
        return out;
    }

    static double num(Map<String, Object> m, String k) { return m != null && m.get(k) instanceof Number n ? n.doubleValue() : 0; }

    /** v = [판매 소진 kg, 손실·결손 kg, 내부사용 kg, 소비 원가, 중량 없는 품목 원가, 구분 매출] */
    static Map<String, Object> metrics(double[] v) {
        double used = v[0] + v[1] + v[2];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used_kg", r(used, 2)); m.put("sale_kg", r(v[0], 2)); m.put("loss_kg", r(v[1], 2)); m.put("internal_kg", r(v[2], 2));
        m.put("cost", Math.round(v[3])); m.put("cost_no_weight", Math.round(v[4])); m.put("sales", Math.round(v[5]));
        m.put("sales_per_kg", used > 0.0001 && v[5] > 0 ? Math.round(v[5] / used) : null);                 // kg당 매출
        m.put("cost_per_kg", used > 0.0001 ? Math.round((v[3] - v[4]) / used) : null);                   // kg당 원가
        m.put("cost_ratio", v[5] > 0 && v[3] > 0 ? r(v[3] / v[5] * 100, 1) : null);                     // 원가율 %
        m.put("loss_ratio", used > 0.0001 ? r(v[1] / used * 100, 1) : null);                            // 손실 비중 %
        return m;
    }

    static double r(double x, int d) { return BigDecimal.valueOf(x).setScale(d, RoundingMode.HALF_UP).doubleValue(); }
}
