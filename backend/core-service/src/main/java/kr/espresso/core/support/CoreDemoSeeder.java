package kr.espresso.core.support;

import kr.espresso.common.DemoIds;
import kr.espresso.common.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 데모 매장·권역·임대·품목·매장별 단가 시드 (core_db 가 비어 있을 때 1회) */
@Component
@Order(1)
public class CoreDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CoreDemoSeeder.class);
    private final JdbcClient db;
    private final boolean enabled;

    public CoreDemoSeeder(JdbcClient db, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.enabled = enabled; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        seedTableware();
        if (db.sql("select count(*) from store.store where site_type = 'STORE'").query(Integer.class).single() > 0) { seedR1(); return; }

        // 권역은 V6 마이그레이션이 시·도 단위로 넣음 (서울특별시·경기도는 데모 고정 ID)
        db.sql("insert into store.region(id, code, name, sort_order) values (?, 'SEOUL', '서울특별시', 1), (?, 'GYEONGGI', '경기도', 9) on conflict (code) do nothing")
                .params(DemoIds.REGION_SEOUL, DemoIds.REGION_GYEONGGI).update();
        store(DemoIds.STORE_GANGNAM, "GN01", "강남점", DemoIds.REGION_SEOUL, "FRANCHISE", "EXCLUSIVE", true, "서울시 강남구 (데모)");
        store(DemoIds.STORE_HONGDAE, "HD01", "홍대점", DemoIds.REGION_SEOUL, "FRANCHISE", "INCLUSIVE", false, "서울시 마포구 (데모)");
        store(DemoIds.STORE_PANGYO, "PG01", "판교점", DemoIds.REGION_GYEONGGI, "DIRECT", "EXCLUSIVE", false, "경기도 성남시 (데모)");
        lease(DemoIds.STORE_GANGNAM, 50_000_000, 4_200_000, 350_000);
        lease(DemoIds.STORE_HONGDAE, 30_000_000, 3_100_000, 250_000);
        lease(DemoIds.STORE_PANGYO, 40_000_000, 3_600_000, 300_000);

        // sku, 이름, 카테고리, 단위, 과세, 본사 기준 공급가, 자산, 바코드
        item("CF-ETH-1K", "에티오피아 예가체프 원두 1kg", "COFFEE", "kg", "TAXABLE", 28_000, false);
        item("CF-COL-1K", "콜롬비아 수프리모 원두 1kg", "COFFEE", "kg", "TAXABLE", 26_000, false);
        item("CF-HOU-1K", "하우스 블렌드 원두 1kg", "COFFEE", "kg", "TAXABLE", 22_000, false);
        item("DA-MLK-1L", "흰우유 1L", "DAIRY", "ea", "EXEMPT", 2_100, false);
        item("DA-OAT-1L", "오트 음료 1L", "DAIRY", "ea", "TAXABLE", 3_800, false);
        item("SY-VAN-1L", "바닐라 시럽 1L", "SYRUP", "ea", "TAXABLE", 9_500, false);
        item("SY-CHO-2K", "초콜릿 파우더 2kg", "SYRUP", "ea", "TAXABLE", 18_000, false);
        item("TB-EARL-50", "얼그레이 티백 50입", "TEA_BASE", "box", "TAXABLE", 12_000, false);
        item("CT-FLT-100", "드립 필터 100매", "COFFEE_TOOL", "box", "TAXABLE", 4_500, false);
        item("DS-CHZ-10", "치즈케이크 10입", "DESSERT", "box", "TAXABLE", 32_000, false);
        item("DS-TIR-8", "티라미수 8입", "DESSERT", "box", "TAXABLE", 28_000, false);
        item("SN-CKE-20", "버터쿠키 20입", "SNACK", "box", "TAXABLE", 15_000, false);
        item("BK-FLR-5K", "박력분 5kg", "BAKING_ING", "ea", "EXEMPT", 9_000, false);
        item("BK-BTR-1K", "무염버터 1kg", "BAKING_ING", "ea", "TAXABLE", 16_000, false);
        item("BT-MLD-6", "머핀틀 6구", "BAKING_TOOL", "ea", "TAXABLE", 7_000, false);
        item("PK-CUP-H12", "종이컵 12oz 1000개", "DISPOSABLE", "box", "TAXABLE", 48_000, false);
        item("PK-CUP-I16", "아이스컵 16oz 1000개", "DISPOSABLE", "box", "TAXABLE", 55_000, false);
        item("PK-LID-1K", "컵 뚜껑 1000개", "DISPOSABLE", "box", "TAXABLE", 21_000, false);
        item("PK-HLD-500", "컵 홀더(슬리브) 500개", "DISPOSABLE", "box", "TAXABLE", 15_000, false);
        item("HY-SAN-4L", "주방 세정제 4L", "HYGIENE", "ea", "TAXABLE", 8_000, false);
        item("OF-RCP-10", "영수증 용지 10롤", "OFFICE", "box", "TAXABLE", 6_000, false);
        item("IT-POS-PRN", "POS 영수증 프린터", "IT", "ea", "TAXABLE", 180_000, false);
        item("TC-ROUTER", "매장용 와이파이 공유기", "TELECOM", "ea", "TAXABLE", 85_000, false);
        item("FN-TBL-2P", "2인 테이블 세트", "FURNITURE", "ea", "TAXABLE", 240_000, true);
        item("RF-SHOW-1", "쇼케이스 냉장고", "REFRIGERATOR", "ea", "TAXABLE", 1_850_000, true);
        item("FZ-UPR-1", "업소용 냉동고", "FREEZER", "ea", "TAXABLE", 1_200_000, true);
        item("CM-ESP-2G", "에스프레소 머신 2그룹", "COFFEE_MACHINE", "ea", "TAXABLE", 9_800_000, true);
        item("IM-50KG", "제빙기 50kg", "ICE_MAKER", "ea", "TAXABLE", 2_300_000, true);

        // 매장별 단가·할인 (요구 7)
        price(DemoIds.STORE_GANGNAM, "CF-ETH-1K", null, "RATE", "0.05");
        price(DemoIds.STORE_GANGNAM, "PK-CUP-I16", 52_000L, null, null);
        price(DemoIds.STORE_HONGDAE, "CF-ETH-1K", 27_000L, null, null);
        price(DemoIds.STORE_HONGDAE, "DS-CHZ-10", null, "AMOUNT", "2000");
        price(DemoIds.STORE_PANGYO, "CF-HOU-1K", null, "RATE", "0.10");

        // 초도 물류 패키지 (매장 생성 마법사)
        UUID pkg = UUID.randomUUID();
        db.sql("insert into catalog.logistics_package(id, name, description) values (?, '기본 초도 물류', '신규 매장 오픈 기본 구성')").param(pkg).update();
        for (String[] p : new String[][]{{"CF-HOU-1K", "10"}, {"DA-MLK-1L", "24"}, {"SY-VAN-1L", "3"}, {"PK-CUP-H12", "2"},
                {"PK-CUP-I16", "2"}, {"PK-LID-1K", "2"}, {"HY-SAN-4L", "2"}, {"OF-RCP-10", "1"}})
            db.sql("insert into catalog.logistics_package_item(package_id, item_id, qty) select ?, id, ? from catalog.item where sku = ?")
                    .params(pkg, new java.math.BigDecimal(p[1]), p[0]).update();
        log.info("core demo data seeded");
        seedR1();
    }

    /**
     * 1차 점검 반영 데모 (기존 DB 에도 1회): 센트럴 베이커리 사이트, 본사 매입 단가(기준가의 80%), 본사 창고 기초 재고
     */
    private void seedR1() {
        if (db.sql("select count(*) from store.store where id = ?").param(DemoIds.BAKERY_CENTRAL).query(Integer.class).single() == 0) {
            db.sql("""
                    insert into store.store(id, code, name, region_id, status, business_type, site_type, vat_display_mode, address, open_date, biz_name)
                    values (?, 'BK01', '센트럴 베이커리', ?, 'OPEN', 'DIRECT', 'BAKERY', 'EXCLUSIVE', '경기도 성남시 (데모)', date '2026-01-15', '카페 드 리옹 베이커리')""")
                    .params(DemoIds.BAKERY_CENTRAL, DemoIds.REGION_GYEONGGI).update();
            log.info("demo bakery site seeded");
        }
        db.sql("update catalog.item set hq_cost = round(base_price * 0.8) where hq_cost is null").update();
        if (db.sql("select count(*) from inventory.stock_balance where store_id = ?").param(Sites.HQ).query(Integer.class).single() == 0) {
            var items = db.sql("select id, hq_cost from catalog.item where is_active and is_hq_supplied and not is_asset").query().listOfRows();
            for (var i : items) {
                java.math.BigDecimal qty = new java.math.BigDecimal(200);
                long cost = ((Number) i.get("hq_cost")).longValue();
                db.sql("insert into inventory.stock_balance(store_id, item_id, qty, avg_cost) values (?,?,?,?)").params(Sites.HQ, i.get("id"), qty, cost).update();
                db.sql("insert into inventory.stock_ledger(store_id, item_id, qty_delta, unit_cost, amount, reason, ref_type, balance_qty) values (?,?,?,?,?, 'OPENING', 'DEMO', ?)")
                        .params(Sites.HQ, i.get("id"), qty, cost, cost * 200, qty).update();
            }
            log.info("demo HQ warehouse opening stock seeded: {} items", items.size());
        }
    }

    /** 식기·포장 구분 데모 품목 (기존 DB 에도 1회 추가: sku 기준) */
    private void seedTableware() {
        if (db.sql("select count(*) from catalog.item where sku like 'TW-%'").query(Integer.class).single() > 0) return;
        item("TW-MUG-350", "머그컵 350ml", "TABLEWARE", "ea", "TAXABLE", 6_500, false);
        item("TW-GLS-400", "아이스 글라스 400ml", "TABLEWARE", "ea", "TAXABLE", 4_800, false);
        item("TW-PLT-DS", "디저트 접시", "TABLEWARE", "ea", "TAXABLE", 5_500, false);
        item("TW-CUT-12", "커트러리 세트(포크·스푼) 12입", "TABLEWARE", "box", "TAXABLE", 18_000, false);
        item("TW-PLT-PRM", "핸드메이드 도자기 플레이트 세트", "TABLEWARE", "ea", "TAXABLE", 180_000, true);   // 기준 10만원 이상 → 비품
        item("PK-BAG-500", "테이크아웃 백 500개", "PACKAGING", "box", "TAXABLE", 32_000, false);
        item("PK-CRR-300", "2구 컵 캐리어 300개", "PACKAGING", "box", "TAXABLE", 27_000, false);
        item("PK-DBX-200", "디저트 박스 200개", "PACKAGING", "box", "TAXABLE", 30_000, false);
        item("DP-STR-1K", "빨대 1000개", "DISPOSABLE", "box", "TAXABLE", 9_000, false);
        item("DP-NAP-2K", "냅킨 2000매", "DISPOSABLE", "box", "TAXABLE", 8_000, false);
        item("DP-SPN-1K", "일회용 스푼 1000개", "DISPOSABLE", "box", "TAXABLE", 11_000, false);
        item("DP-FRK-1K", "일회용 포크 1000개", "DISPOSABLE", "box", "TAXABLE", 11_000, false);
        log.info("tableware/packaging demo items seeded");
    }

    private void store(UUID id, String code, String name, UUID region, String type, String vat, boolean ownerPay, String addr) {
        db.sql("""
                insert into store.store(id, code, name, region_id, status, business_type, vat_display_mode,
                  owner_payroll_detail_visible, address, open_date, biz_name)
                values (?,?,?,?, 'OPEN', ?,?,?,?, date '2026-01-15', ?)""")
                .params(id, code, name, region, type, vat, ownerPay, addr, "카페 드 리옹 " + name).update();
    }

    private void lease(UUID store, long deposit, long rent, long fee) {
        db.sql("insert into store.store_lease(store_id, deposit, monthly_rent, maintenance_fee, payment_day, start_date, end_date) values (?,?,?,?,25, date '2026-01-01', date '2028-12-31')")
                .params(store, deposit, rent, fee).update();
    }

    private void item(String sku, String name, String cat, String unit, String tax, long price, boolean asset) {
        db.sql("""
                insert into catalog.item(sku, name, category_id, unit, tax_type, base_price, is_asset)
                select ?, ?, id, ?, ?, ?, ? from catalog.category where code = ?""")
                .params(sku, name, unit, tax, price, asset, cat).update();
    }

    private void price(UUID store, String sku, Long unitPrice, String dType, String dVal) {
        db.sql("""
                insert into catalog.store_price(store_id, item_id, unit_price, discount_type, discount_value, valid_from)
                select ?, id, ?, ?, cast(? as numeric), date '2026-01-01' from catalog.item where sku = ?""")
                .params(store, unitPrice, dType, dVal, sku).update();
    }
}
