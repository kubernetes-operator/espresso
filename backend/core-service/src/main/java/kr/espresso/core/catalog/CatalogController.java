package kr.espresso.core.catalog;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/core/catalog")
public class CatalogController {
    private final JdbcClient db;
    private final StoreScope scope;

    public CatalogController(JdbcClient db, StoreScope scope) { this.db = db; this.scope = scope; }

    @GetMapping("/divisions")
    public List<Map<String, Object>> divisions() {
        CurrentUser.get();
        return db.sql("select code, name from catalog.division order by sort_order").query().listOfRows();
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        CurrentUser.get();
        return db.sql("""
                select c.id, c.code, c.name, c.division_code, d.name as division_name, c.is_asset, c.asset_threshold, c.is_active, c.parent_id, c.sku_prefix
                from catalog.category c join catalog.division d on d.code = c.division_code
                order by d.sort_order, c.sort_order""").query().listOfRows();
    }

    public record ThresholdReq(Long assetThreshold) {}

    /** 카테고리 비품 기준 단가 (예: 식기 10만원 이상 = 비품). 비우면 기준 없음. 해당 카테고리 품목의 자산 여부를 다시 계산 */
    @PutMapping("/categories/{id}/asset-threshold")
    public Map<String, Object> setThreshold(@PathVariable UUID id, @RequestBody ThresholdReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        if (r.assetThreshold() != null && r.assetThreshold() <= 0) throw ApiException.bad("기준 단가는 0보다 커야 합니다.");
        db.sql("update catalog.category set asset_threshold = ? where id = ?").params(r.assetThreshold(), id).update();
        int n = db.sql("""
                update catalog.item i set is_asset = (c.is_asset or (c.asset_threshold is not null and i.base_price >= c.asset_threshold)), updated_at = now()
                from catalog.category c where c.id = i.category_id and c.id = ?""").param(id).update();
        return Map.of("ok", true, "items", n);
    }

    public record PrefixReq(String skuPrefix) {}

    /** 카테고리 품목 코드 접두어 (예: 커피 CF → CF-0001) */
    @PutMapping("/categories/{id}/sku-prefix")
    public Map<String, Object> setPrefix(@PathVariable UUID id, @RequestBody PrefixReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        String p = r.skuPrefix() == null ? "" : r.skuPrefix().trim().toUpperCase();
        if (!p.matches("[A-Z0-9]{1,6}")) throw ApiException.bad("접두어는 영문 대문자·숫자 1~6자입니다.");
        db.sql("update catalog.category set sku_prefix = ? where id = ?").params(p, id).update();
        return Map.of("ok", true);
    }

    /**
     * 품목 코드 자동 생성: 카테고리 접두어 + 4자리 연번 (예: CF-0007).
     * 같은 접두어로 시작하는 코드 중 숫자 4자리 형식의 최댓값 + 1. 화면에서 바꿔 쓸 수 있다.
     */
    @GetMapping("/next-sku")
    public Map<String, Object> nextSku(@RequestParam UUID categoryId) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        return Map.of("sku", nextSkuFor(categoryId));
    }

    String nextSkuFor(UUID categoryId) {
        String prefix = db.sql("select coalesce(sku_prefix, upper(left(code, 2))) from catalog.category where id = ?").param(categoryId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("카테고리"));
        Integer max = db.sql("select coalesce(max(substring(sku from '^' || ? || '-(\\d{4})$')::int), 0) from catalog.item where sku ~ ('^' || ? || '-\\d{4}$')")
                .params(prefix, prefix).query(Integer.class).single();
        return prefix + "-" + String.format("%04d", max + 1);
    }

    static boolean hqView(CurrentUser u) { return u.has(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR); }

    /** 자산 여부: 설비 카테고리이거나, 카테고리 비품 기준 단가 이상 (고가 식기 → 비품) */
    boolean assetFor(UUID categoryId, long basePrice) {
        return db.sql("select is_asset or (asset_threshold is not null and ? >= asset_threshold) from catalog.category where id = ?")
                .params(basePrice, categoryId).query(Boolean.class).single();
    }

    /** 품목 목록. storeId 가 있으면 그 매장의 적용 단가·할인 포함 (요구 7) */
    @GetMapping("/items")
    public List<Map<String, Object>> items(@RequestParam(required = false) UUID storeId,
                                           @RequestParam(required = false) String division,
                                           @RequestParam(required = false) UUID categoryId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "true") boolean activeOnly) {
        CurrentUser u = CurrentUser.get();
        if (storeId != null) scope.check(u, storeId);
        return db.sql("""
                select i.id, i.sku, i.name, i.unit, i.net_weight_g, i.tax_type, i.base_price, i.hq_cost, i.is_asset, i.is_active, i.barcode,
                       c.consumption_group,
                       i.category_id, c.name as category_name, c.division_code, d.name as division_name,
                       sp.id as store_price_id, sp.unit_price as store_unit_price, sp.discount_type, sp.discount_value,
                       coalesce(sp.unit_price, i.base_price) as list_price
                from catalog.item i join catalog.category c on c.id = i.category_id join catalog.division d on d.code = c.division_code
                left join lateral (select * from catalog.store_price p where p.store_id = cast(:store as uuid) and p.item_id = i.id
                    and p.valid_from <= current_date and (p.valid_to is null or p.valid_to >= current_date)
                    order by p.valid_from desc limit 1) sp on true
                where (cast(:div as varchar) is null or c.division_code = cast(:div as varchar)) and (cast(:cat as uuid) is null or i.category_id = :cat)
                  and (cast(:q as varchar) is null or i.name ilike '%' || cast(:q as varchar) || '%' or i.sku ilike '%' || cast(:q as varchar) || '%')
                  and (not :active or i.is_active)
                order by d.sort_order, c.sort_order, i.name""")
                .param("store", storeId).param("div", division).param("cat", categoryId).param("q", q == null || q.isBlank() ? null : q)
                .param("active", activeOnly).query().listOfRows()
                .stream().map(r -> { if (hqView(u)) return r; Map<String, Object> m = new LinkedHashMap<>(r); m.remove("hq_cost"); return m; }).toList();   // 본사 매입가는 물류 관리자 이상만
    }

    public record ItemReq(String sku, String name, UUID categoryId, String unit, String taxType, Long basePrice,
                          Boolean isActive, String barcode, Long hqCost, BigDecimal netWeightG) {}

    @PostMapping("/items")
    public Map<String, Object> createItem(@RequestBody ItemReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        if (r.name() == null || r.name().isBlank() || r.categoryId() == null || r.basePrice() == null) throw ApiException.bad("품명·카테고리·기준가는 필수입니다.");
        String sku = r.sku() == null || r.sku().isBlank() ? nextSkuFor(r.categoryId()) : r.sku().trim().toUpperCase();   // 비우면 자동 생성
        checkSku(sku, null);
        boolean asset = assetFor(r.categoryId(), r.basePrice());
        UUID id = UUID.randomUUID();
        db.sql("insert into catalog.item(id, sku, name, category_id, unit, tax_type, base_price, hq_cost, is_asset, barcode, net_weight_g) values (?,?,?,?,?,?,?,?,?,?,?)")
                .params(id, sku, r.name().trim(), r.categoryId(), Objects.requireNonNullElse(r.unit(), "ea"),
                        Objects.requireNonNullElse(r.taxType(), "TAXABLE"), r.basePrice(), r.hqCost(), asset, r.barcode(),
                        r.netWeightG() == null || r.netWeightG().signum() <= 0 ? null : r.netWeightG()).update();
        return Map.of("id", id, "sku", sku);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable UUID id, @RequestBody ItemReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        if (r.sku() != null && !r.sku().isBlank()) {
            String sku = r.sku().trim().toUpperCase();
            checkSku(sku, id);
            db.sql("update catalog.item set sku = ? where id = ?").params(sku, id).update();
        }
        if (r.hqCost() != null) db.sql("update catalog.item set hq_cost = ? where id = ?").params(r.hqCost() < 0 ? null : r.hqCost(), id).update();
        if (r.netWeightG() != null)   // 0 이하 = 지움
            db.sql("update catalog.item set net_weight_g = ? where id = ?").params(r.netWeightG().signum() <= 0 ? null : r.netWeightG(), id).update();
        db.sql("""
                update catalog.item set name = coalesce(cast(:n as varchar), name), unit = coalesce(cast(:u as varchar), unit),
                  tax_type = coalesce(cast(:t as varchar), tax_type), base_price = coalesce(cast(:p as bigint), base_price),
                  is_active = coalesce(cast(:a as boolean), is_active), category_id = coalesce(cast(:c as uuid), category_id),
                  updated_at = now() where id = :id""")
                .param("n", r.name()).param("u", r.unit()).param("t", r.taxType()).param("p", r.basePrice())
                .param("a", r.isActive()).param("c", r.categoryId()).param("id", id).update();
        db.sql("""
                update catalog.item i set is_asset = (c.is_asset or (c.asset_threshold is not null and i.base_price >= c.asset_threshold))
                from catalog.category c where c.id = i.category_id and i.id = ?""").param(id).update();
        return Map.of("id", id);
    }

    void checkSku(String sku, UUID self) {
        if (!sku.matches("[A-Z0-9][A-Z0-9-]{1,31}")) throw ApiException.bad("품목 코드는 영문 대문자·숫자·- 2~32자입니다.");
        if (db.sql("select count(*) from catalog.item where sku = ? and (cast(? as uuid) is null or id <> ?)").params(sku, self, self).query(Integer.class).single() > 0)
            throw ApiException.conflict("이미 쓰는 품목 코드입니다: " + sku);
    }

    public record StorePriceReq(UUID storeId, UUID itemId, Long unitPrice, String discountType, BigDecimal discountValue) {}

    /** 매장별 단가·할인 설정: 기존 적용분은 어제자로 종료하고 오늘부터 새 값 적용 (이력 보존) */
    @PostMapping("/store-prices")
    public Map<String, Object> setStorePrice(@RequestBody StorePriceReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        if (r.discountType() != null && !Set.of("RATE", "AMOUNT").contains(r.discountType())) throw ApiException.bad("할인 유형은 RATE / AMOUNT 입니다.");
        if ("RATE".equals(r.discountType()) && (r.discountValue() == null || r.discountValue().compareTo(BigDecimal.ONE) >= 0))
            throw ApiException.bad("할인율은 0~1 사이 값입니다 (예: 0.05 = 5%).");
        LocalDate today = LocalDate.now();
        db.sql("delete from catalog.store_price where store_id = ? and item_id = ? and valid_from = ?").params(r.storeId(), r.itemId(), today).update();
        db.sql("update catalog.store_price set valid_to = ? where store_id = ? and item_id = ? and (valid_to is null or valid_to >= ?)")
                .params(today.minusDays(1), r.storeId(), r.itemId(), today).update();
        boolean clear = r.unitPrice() == null && r.discountType() == null;
        if (!clear)
            db.sql("insert into catalog.store_price(store_id, item_id, unit_price, discount_type, discount_value, valid_from, created_by) values (?,?,?,?,?,?,?)")
                    .params(r.storeId(), r.itemId(), r.unitPrice(), r.discountType(), r.discountType() == null ? null : r.discountValue(), today, u.id()).update();
        return Map.of("ok", true, "cleared", clear);
    }

    @GetMapping("/packages")
    public List<Map<String, Object>> packages() {
        CurrentUser.get();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : db.sql("select id, name, description from catalog.logistics_package where is_active order by name").query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            m.put("items", db.sql("select pi.item_id, pi.qty, i.name, i.sku from catalog.logistics_package_item pi join catalog.item i on i.id = pi.item_id where pi.package_id = ?")
                    .param(p.get("id")).query().listOfRows());
            out.add(m);
        }
        return out;
    }
}
