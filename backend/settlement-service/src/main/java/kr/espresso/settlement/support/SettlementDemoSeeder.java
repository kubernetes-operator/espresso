package kr.espresso.settlement.support;

import kr.espresso.common.DemoIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 데모: 매장별 로열티 정책 (강남 3% / 홍대 2%+30만 / 판교 직영 없음) */
@Component
public class SettlementDemoSeeder implements ApplicationRunner {
    private final JdbcClient db;
    private final boolean enabled;

    public SettlementDemoSeeder(JdbcClient db, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.enabled = enabled; }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (db.sql("select count(*) from stl.royalty_policy").query(Integer.class).single() == 0) seedRoyalty();
        if (db.sql("select count(*) from stl.investment_item").query(Integer.class).single() == 0) seedInvestment();
        seedFunding();
    }

    /** 2차 점검 반영 데모 (가상): 강남 임차보증금을 투자자A 출자로 → 이익금 배분 비율 = 출자 / 구성 총액 */
    void seedFunding() {
        if (db.sql("select count(*) from stl.investment_item where funding_source = 'INVESTOR'").query(Integer.class).single() > 0) return;
        db.sql("""
                update stl.investment_item set funding_source = 'INVESTOR', description = '임차보증금 (투자자A 출자)',
                  investor_id = (select id from stl.investor where user_id = ?)
                where store_id = ? and category = 'DEPOSIT'""").params(DemoIds.INVESTOR1, DemoIds.STORE_GANGNAM).update();
    }

    void seedRoyalty() {
        String sql = "insert into stl.royalty_policy(store_id, type, base, rate, fixed_amount, valid_from, created_by) values (?,?,?,?,?, date '2026-01-01', ?)";
        db.sql(sql).params(DemoIds.STORE_GANGNAM, "RATE", "NET_SALES_EXCL_VAT", new BigDecimal("0.03"), null, DemoIds.ADMIN).update();
        db.sql(sql).params(DemoIds.STORE_HONGDAE, "RATE_PLUS_FIXED", "NET_SALES_INCL_VAT", new BigDecimal("0.02"), 300_000L, DemoIds.ADMIN).update();
        db.sql(sql).params(DemoIds.STORE_PANGYO, "NONE", "NET_SALES_EXCL_VAT", null, null, DemoIds.ADMIN).update();
    }

    void seedInvestment() {
        // 투자금 (매장 구성 총액) + 설비 자산(감가상각)
        Object[][] inv = {
                {DemoIds.STORE_GANGNAM, "INTERIOR", "인테리어 공사", 85_000_000L}, {DemoIds.STORE_GANGNAM, "EQUIPMENT", "에스프레소 머신·그라인더", 14_500_000L},
                {DemoIds.STORE_GANGNAM, "DEPOSIT", "임차보증금", 50_000_000L}, {DemoIds.STORE_GANGNAM, "INITIAL_STOCK", "초도 물품", 3_200_000L},
                {DemoIds.STORE_HONGDAE, "INTERIOR", "인테리어 공사", 62_000_000L}, {DemoIds.STORE_HONGDAE, "EQUIPMENT", "머신·냉장 설비", 11_800_000L},
                {DemoIds.STORE_HONGDAE, "DEPOSIT", "임차보증금", 30_000_000L}, {DemoIds.STORE_HONGDAE, "PREMIUM", "권리금", 20_000_000L},
                {DemoIds.STORE_PANGYO, "INTERIOR", "인테리어 공사", 70_000_000L}, {DemoIds.STORE_PANGYO, "EQUIPMENT", "머신·제빙기·쇼케이스", 16_300_000L},
                {DemoIds.STORE_PANGYO, "DEPOSIT", "임차보증금", 40_000_000L}};
        for (Object[] i : inv) {
            java.util.UUID asset = null;
            if ("EQUIPMENT".equals(i[1])) {
                asset = java.util.UUID.randomUUID();
                db.sql("insert into stl.fixed_asset(id, store_id, name, category_code, acquired_date, cost, useful_life_months, source_type) values (?,?,?, 'COFFEE_MACHINE', date '2026-01-10', ?, 60, 'ONBOARDING')")
                        .params(asset, i[0], i[2], i[3]).update();
            }
            db.sql("insert into stl.investment_item(store_id, category, description, amount, invested_on, asset_id, created_by) values (?,?,?,?, date '2026-01-10', ?, ?)")
                    .params(i[0], i[1], i[2], i[3], asset, DemoIds.ADMIN).update();
        }
        java.util.UUID investor = java.util.UUID.randomUUID();
        db.sql("insert into stl.investor(id, user_id, display_name) values (?,?, '투자자A')").params(investor, DemoIds.INVESTOR1).update();
        db.sql("insert into stl.investor_disclosure(investor_id, store_id, mode, ratio, show_settlement, visible_from, created_by) values (?,?, 'RATIO', 0.30, true, date '2026-01-01', ?)")
                .params(investor, DemoIds.STORE_GANGNAM, DemoIds.ADMIN).update();
    }
}
