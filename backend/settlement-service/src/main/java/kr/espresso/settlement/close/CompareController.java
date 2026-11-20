package kr.espresso.settlement.close;

import kr.espresso.common.*;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 요구 17: 매장 비교 (손익 지표) + 리포트용 내부 요약 */
@RestController
public class CompareController {
    private final SettlementService svc;
    private final CoreClient core;

    public CompareController(SettlementService svc, CoreClient core) { this.svc = svc; this.core = core; }

    @GetMapping("/api/stl/compare")
    public List<Map<String, Object>> compare(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var st : core.myStores()) {
            if (st.get("site_type") != null && !"STORE".equals(st.get("site_type"))) continue;   // 본사·베이커리 제외
            UUID id = UUID.fromString(st.get("id").toString());
            if (!u.isHqAdmin() && u.assignments().stream().noneMatch(a -> Roles.OWNER.equals(a.role()) && id.equals(a.scopeId()))) continue;
            Map<String, Object> s = svc.summary(id, period);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store_id", id); m.put("store_name", st.get("name")); m.put("region_name", st.get("region_name"));
            for (String k : List.of("net_sales", "cogs", "gross_profit", "labor", "rent", "utility", "stock_loss", "operating_profit", "royalty", "settlement_amount", "closed", "ratios"))
                m.put(k, s.get(k));
            out.add(m);
        }
        return out;
    }

    static final List<String> BENCH = List.of("net_sales", "operating_profit", "settlement_amount");

    /**
     * 비교 기준값: 전 매장(매장만) 지표의 최댓값·최솟값·평균 — 매장 이름은 내보내지 않음 (점주용 상/하위 비교)
     */
    @GetMapping("/api/stl/compare/benchmark")
    public Map<String, Object> benchmark(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        Map<String, List<Double>> vals = new LinkedHashMap<>();
        int n = 0;
        for (var st : core.allStores()) {
            if (st.get("site_type") != null && !"STORE".equals(st.get("site_type"))) continue;
            Map<String, Object> s = svc.summary(UUID.fromString(st.get("id").toString()), period);
            if (((Number) s.getOrDefault("net_sales", 0)).longValue() == 0) continue;   // 매출 없는 매장(오픈 전) 제외
            n++;
            for (String k : BENCH) vals.computeIfAbsent(k, x -> new ArrayList<>()).add(((Number) s.get(k)).doubleValue());
            Map<?, ?> r = (Map<?, ?>) s.get("ratios");
            for (String k : List.of("cogs", "labor", "rent", "loss", "margin"))
                if (r != null && r.get(k) != null) vals.computeIfAbsent("r_" + k, x -> new ArrayList<>()).add(((Number) r.get(k)).doubleValue());
        }
        return Map.of("count", n, "metrics", stats(vals));
    }

    public static Map<String, Object> stats(Map<String, List<Double>> vals) {
        Map<String, Object> out = new LinkedHashMap<>();
        vals.forEach((k, v) -> out.put(k, Map.of("max", v.stream().mapToDouble(d -> d).max().orElse(0), "min", v.stream().mapToDouble(d -> d).min().orElse(0),
                "avg", Math.round(v.stream().mapToDouble(d -> d).average().orElse(0) * 10) / 10.0)));
        return out;
    }

    /** 리포트 스냅샷용 (core 내부 호출) */
    @GetMapping("/internal/settlement/summary")
    public Map<String, Object> internal(@RequestParam UUID storeId, @RequestParam String period) {
        return svc.summary(storeId, period);
    }
}
