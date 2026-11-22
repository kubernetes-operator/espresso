package kr.espresso.core.report;

import kr.espresso.common.DemoIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * 데모: 재고 소비 분석 화면용 과거 소비 이력 (최근 12개월, 매주 일요일).
 * 주간 음료·디저트 매출에서 매장별 kg당 매출(가상)을 역산해 원두·베이커리 소비량을 만들고,
 * 같은 양의 입고(RECEIPT)와 소진(판매 소진·손실·결손·내부사용)을 짝으로 넣어 재고 잔량은 바뀌지 않게 함.
 */
@Component
@Order(6)
public class ConsumptionDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ConsumptionDemoSeeder.class);
    static final String REF = "DEMO_CONSUMPTION";
    private final JdbcClient db;
    private final boolean enabled;

    public ConsumptionDemoSeeder(JdbcClient db, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.enabled = enabled; }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        // 데모 디저트 품목 중량 (가상값)
        for (Object[] w : new Object[][]{{"DS-CHZ-10", 1100}, {"DS-TIR-8", 960}, {"SN-CKE-20", 300}})
            db.sql("update catalog.item set net_weight_g = ? where sku = ? and net_weight_g is null").params(w[1], w[0]).update();
        if (db.sql("select count(*) from inventory.stock_ledger where ref_type = ?").param(REF).query(Integer.class).single() > 0) return;
        UUID bean = item("CF-HOU-1K"), cake = item("DS-CHZ-10"), tira = item("DS-TIR-8");
        if (bean == null || cake == null || tira == null) return;
        // 매장별 kg당 매출(가상): 원두 1kg당 음료 매출, 베이커리 1kg당 디저트 매출, 원두 손실률, 베이커리 손실률
        Object[][] stores = {
                {DemoIds.STORE_GANGNAM, 245_000.0, 58_000.0, 0.035, 0.06},
                {DemoIds.STORE_HONGDAE, 205_000.0, 49_000.0, 0.07, 0.11},
                {DemoIds.STORE_PANGYO, 228_000.0, 54_000.0, 0.045, 0.08}};
        Random rnd = new Random(11);
        LocalDate lastSun = LocalDate.now(ZoneId.of("Asia/Seoul")).with(DayOfWeek.MONDAY).minusDays(1);
        int n = 0;
        for (Object[] st : stores) {
            UUID store = (UUID) st[0];
            for (LocalDate sun = lastSun.minusWeeks(52); !sun.isAfter(lastSun); sun = sun.plusWeeks(1)) {
                LocalDate mon = sun.minusDays(6);
                long bev = sales(store, mon, sun, "BEVERAGE"), des = sales(store, mon, sun, "DESSERT");
                if (bev <= 0) continue;
                double drift = 1 + 0.04 * Math.sin(sun.getDayOfYear() / 365.0 * 2 * Math.PI);   // 계절 변동
                double beanKg = bev / ((Double) st[1] * drift * (0.94 + rnd.nextDouble() * 0.12));
                double bakeKg = des / ((Double) st[2] * (0.93 + rnd.nextDouble() * 0.14));
                n += consume(store, bean, 1000, beanKg, (Double) st[3], sun, rnd);
                n += consume(store, cake, 1100, bakeKg * 0.6, (Double) st[4], sun, rnd);
                n += consume(store, tira, 960, bakeKg * 0.4, (Double) st[4], sun, rnd);
            }
        }
        log.info("demo consumption history seeded: {} ledger rows", n);
    }

    UUID item(String sku) { return db.sql("select id from catalog.item where sku = ?").param(sku).query(UUID.class).optional().orElse(null); }

    long sales(UUID store, LocalDate from, LocalDate to, String div) {
        return db.sql("""
                select coalesce(sum(x.supply_amount),0) from sales.daily_sales d join sales.daily_sales_division x on x.daily_sales_id = d.id
                where d.store_id = ? and d.sales_date between ? and ? and x.division_code = ?""").params(store, from, to, div).query(Long.class).single();
    }

    /** 입고 +q 후 판매 소진·손실(또는 결손)·내부사용으로 같은 양을 소진 (잔량 불변) */
    int consume(UUID store, UUID item, double weightG, double kg, double lossRate, LocalDate sun, Random rnd) {
        double qty = Math.round(kg * 1000 / weightG * 10) / 10.0;
        if (qty <= 0) return 0;
        long cost = db.sql("select base_price from catalog.item where id = ?").param(item).query(Long.class).single();
        double bal = db.sql("select coalesce(max(qty),0) from inventory.stock_balance where store_id = ? and item_id = ?").params(store, item).query(Double.class).single();
        OffsetDateTime at = sun.atTime(21, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        double loss = Math.round(qty * lossRate * (0.7 + rnd.nextDouble() * 0.6) * 10) / 10.0;
        double internal = Math.round(qty * 0.02 * 10) / 10.0;
        double sale = Math.round((qty - loss - internal) * 10) / 10.0;
        String lossReason = rnd.nextBoolean() ? "LOSS" : "SHORTAGE";
        int n = 0;
        n += row(store, item, qty, cost, "RECEIPT", bal, at.minusDays(6));
        n += row(store, item, -sale, cost, "SALE", bal, at);
        n += row(store, item, -loss, cost, lossReason, bal, at);
        n += row(store, item, -internal, cost, "INTERNAL_USE", bal, at);
        return n;
    }

    int row(UUID store, UUID item, double q, long cost, String reason, double bal, OffsetDateTime at) {
        if (q == 0) return 0;
        db.sql("insert into inventory.stock_ledger(store_id, item_id, qty_delta, unit_cost, amount, reason, ref_type, balance_qty, occurred_at, actor_id) values (?,?,?,?,?,?,?,?,?,?)")
                .params(store, item, q, cost, Math.round(q * cost), reason, REF, bal, at, DemoIds.ADMIN).update();
        return 1;
    }
}
