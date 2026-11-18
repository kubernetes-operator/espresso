package kr.espresso.core.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** settlement 등 내부 서비스용 (외부 비노출) */
@RestController
@RequestMapping("/internal")
public class InternalController {
    private final JdbcClient db;
    public InternalController(JdbcClient db) { this.db = db; }

    /** 현재 재고 금액 (결산 기말재고) */
    @GetMapping("/inventory/value")
    public Map<String, Object> stockValue(@RequestParam UUID storeId) {
        Long v = db.sql("select coalesce(round(sum(qty * avg_cost)), 0) from inventory.stock_balance where store_id = ?").param(storeId).query(Long.class).single();
        return Map.of("storeId", storeId, "value", v);
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        return db.sql("select s.id, s.code, s.name, s.region_id, r.name region_name, s.business_type, s.biz_reg_no, s.biz_name, s.open_date, s.owner_payroll_detail_visible, s.manager_payroll_detail_visible from store.store s left join store.region r on r.id = s.region_id order by s.code")
                .query().listOfRows();
    }

    @GetMapping("/stores/{id}/leases")
    public List<Map<String, Object>> leases(@PathVariable UUID id) {
        return db.sql("select * from store.store_lease where store_id = ? order by start_date").param(id).query().listOfRows();
    }
}
