package kr.espresso.core.catalog;

import kr.espresso.common.ApiException;
import kr.espresso.common.Vat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 요구 7·8: 매장별 단가·할인 적용, 라인 금액·부가세 계산 */
@Service
public class PriceService {
    private final JdbcClient db;
    public PriceService(JdbcClient db) { this.db = db; }

    public record Effective(UUID itemId, String sku, String name, UUID categoryId, String unit, String taxType,
                            boolean isAsset, long basePrice, long listPrice, String discountType, BigDecimal discountValue) {}

    public record Line(long listUnitPrice, long discountAmount, long supply, long vat, long total) {}

    public Effective effective(UUID storeId, UUID itemId) {
        var rows = db.sql("""
                select i.id, i.sku, i.name, i.category_id, i.unit, i.tax_type, i.is_asset, i.base_price,
                       sp.unit_price, sp.discount_type, sp.discount_value
                from catalog.item i
                left join lateral (select * from catalog.store_price p where p.store_id = ? and p.item_id = i.id
                    and p.valid_from <= current_date and (p.valid_to is null or p.valid_to >= current_date)
                    order by p.valid_from desc limit 1) sp on true
                where i.id = ? and i.is_active""").params(storeId, itemId).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("품목");
        var r = rows.get(0);
        long base = ((Number) r.get("base_price")).longValue();
        Long sp = r.get("unit_price") == null ? null : ((Number) r.get("unit_price")).longValue();
        return new Effective((UUID) r.get("id"), (String) r.get("sku"), (String) r.get("name"), (UUID) r.get("category_id"),
                (String) r.get("unit"), (String) r.get("tax_type"), (Boolean) r.get("is_asset"), base, sp == null ? base : sp,
                (String) r.get("discount_type"), (BigDecimal) r.get("discount_value"));
    }

    /** 공급가 기준 라인 계산: 단가×수량 − 할인(RATE: 금액×율, AMOUNT: 단위당 금액×수량), 원 단위 절사 */
    public static Line calc(long listUnitPrice, BigDecimal qty, String discountType, BigDecimal discountValue, String taxType) {
        BigDecimal gross = BigDecimal.valueOf(listUnitPrice).multiply(qty);
        BigDecimal disc = BigDecimal.ZERO;
        if ("RATE".equals(discountType) && discountValue != null) disc = gross.multiply(discountValue);
        else if ("AMOUNT".equals(discountType) && discountValue != null) disc = discountValue.multiply(qty);
        long d = disc.setScale(0, RoundingMode.FLOOR).longValue();
        long supply = gross.setScale(0, RoundingMode.FLOOR).longValue() - d;
        var a = Vat.fromSupply(Math.max(supply, 0), taxType);
        return new Line(listUnitPrice, d, a.supply(), a.vat(), a.total());
    }
}
