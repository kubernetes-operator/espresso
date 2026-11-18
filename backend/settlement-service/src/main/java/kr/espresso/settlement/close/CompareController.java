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

    /** 리포트 스냅샷용 (core 내부 호출) */
    @GetMapping("/internal/settlement/summary")
    public Map<String, Object> internal(@RequestParam UUID storeId, @RequestParam String period) {
        return svc.summary(storeId, period);
    }
}
