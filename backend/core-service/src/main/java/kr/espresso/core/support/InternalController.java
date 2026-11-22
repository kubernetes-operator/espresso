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
        return db.sql("select s.id, s.code, s.name, s.site_type, s.region_id, r.name region_name, s.business_type, s.biz_reg_no, s.biz_name, s.open_date, s.owner_payroll_detail_visible, s.manager_payroll_detail_visible from store.store s left join store.region r on r.id = s.region_id order by s.code")
                .query().listOfRows();
    }

    /** 월별 순매출 (투자자 분석 등 settlement 내부 호출) — 결산 반영 전 과거 실적 포함 */
    @GetMapping("/sales/monthly")
    public List<Map<String, Object>> monthlySales(@RequestParam UUID storeId, @RequestParam String from, @RequestParam String to) {
        return db.sql("""
                select to_char(sales_date, 'YYYYMM') period, sum(gross_supply + exempt_supply - discount_amount - refund_amount) net_sales
                from sales.daily_sales where store_id = ? and sales_date between to_date(?, 'YYYYMM') and (to_date(?, 'YYYYMM') + interval '1 month' - interval '1 day')
                group by 1 order by 1""").params(storeId, from, to).query().listOfRows();
    }

    /**
     * 본사 물류 이득: 기간 안에 발송한 본사 물류 품목의 매장 공급가(무상 재발송 제외) − 본사 창고 출고 원가(이동평균)
     */
    @GetMapping("/hq/logistics")
    public Map<String, Object> hqLogistics(@RequestParam String from, @RequestParam String to) {
        Map<String, Object> m = new LinkedHashMap<>(db.sql("""
                select coalesce(sum(l.supply_amount), 0) revenue, count(distinct r.id) requests
                from logistics.request r join logistics.request_line l on l.request_id = r.id
                where l.source_type = 'HQ' and l.line_status <> 'CANCELLED' and r.kind = 'NORMAL' and r.shipped_at is not null
                  and (r.shipped_at at time zone 'Asia/Seoul')::date between cast(? as date) and cast(? as date)""").params(from, to).query().singleRow());
        m.put("cost", db.sql("""
                select coalesce(-sum(amount), 0) from inventory.stock_ledger where store_id = '00000000-0000-0000-0000-0000000000a0' and reason = 'TRANSFER_OUT'
                  and (occurred_at at time zone 'Asia/Seoul')::date between cast(? as date) and cast(? as date)""").params(from, to).query(Long.class).single());
        m.put("by_store", db.sql("""
                select r.store_id, s.name store_name, coalesce(sum(l.supply_amount), 0) revenue
                from logistics.request r join logistics.request_line l on l.request_id = r.id join store.store s on s.id = r.store_id
                where l.source_type = 'HQ' and l.line_status <> 'CANCELLED' and r.kind = 'NORMAL' and r.shipped_at is not null
                  and (r.shipped_at at time zone 'Asia/Seoul')::date between cast(? as date) and cast(? as date) group by 1, 2 order by 2""").params(from, to).query().listOfRows());
        return m;
    }

    @GetMapping("/stores/{id}/leases")
    public List<Map<String, Object>> leases(@PathVariable UUID id) {
        return db.sql("select * from store.store_lease where store_id = ? order by start_date").param(id).query().listOfRows();
    }
}
