package kr.espresso.core.sales;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.common.Vat;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 일 매출 (요구 11): 1차 수기·엑셀, 2차 POS 어댑터.
 * 입력은 POS 마감 화면과 같은 '결제 금액(VAT 포함)' 기준 → 공급가·세액으로 분리 저장
 */
@Service
public class SalesService {
    static final List<String> DIVS = List.of("BEVERAGE", "DESSERT", "TABLEWARE", "MANAGEMENT");
    private final JdbcClient db;
    private final StoreScope scope;
    private final Outbox outbox;
    private final DocTrail trail;

    public SalesService(JdbcClient db, StoreScope scope, Outbox outbox, DocTrail trail) {
        this.db = db; this.scope = scope; this.outbox = outbox; this.trail = trail;
    }

    public record DayReq(Long beverage, Long dessert, Long tableware, Long etc, Long exempt, Long discount, Long refund,
                         Long card, Long cash, Long otherPay, Integer customers, String memo, Boolean close) {}

    @Transactional
    public UUID upsert(UUID store, LocalDate date, DayReq r, String source, UUID batch, CurrentUser u) {
        scope.requireRoleOn(u, store, Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN);
        if (date.isAfter(LocalDate.now())) throw ApiException.bad("미래 날짜 매출은 입력할 수 없습니다.");
        var cur = db.sql("select id, status from sales.daily_sales where store_id = ? and sales_date = ?").params(store, date).query().listOfRows();
        if (!cur.isEmpty() && "CLOSED".equals(cur.get(0).get("status"))) throw ApiException.conflict(date + " 매출은 이미 마감되었습니다.");
        long[] gross = {nz(r.beverage()), nz(r.dessert()), nz(r.tableware()), nz(r.etc())};
        long grossTotal = Arrays.stream(gross).sum();
        long exempt = nz(r.exempt());
        long disc = nz(r.discount()), refund = nz(r.refund());
        if (disc + refund > grossTotal + exempt) throw ApiException.bad("할인·환불이 매출보다 큽니다.");
        Vat.Amount[] div = new Vat.Amount[DIVS.size()];
        long grossSupply = 0;
        for (int i = 0; i < DIVS.size(); i++) { div[i] = Vat.fromTotal(gross[i], Vat.TAXABLE); grossSupply += div[i].supply(); }
        long discSupply = Vat.fromTotal(disc, Vat.TAXABLE).supply();
        long refundSupply = Vat.fromTotal(refund, Vat.TAXABLE).supply();
        long netTotal = grossTotal + exempt - disc - refund;
        long netSupply = grossSupply + exempt - discSupply - refundSupply;
        long vat = netTotal - netSupply;
        long card = nz(r.card()), cash = nz(r.cash()), other = nz(r.otherPay());
        if (card + cash + other == 0) card = netTotal;
        if (card + cash + other != netTotal) throw ApiException.bad("결제수단 합계(" + (card + cash + other) + ")가 실 매출(" + netTotal + ")과 다릅니다.");
        UUID id = cur.isEmpty() ? UUID.randomUUID() : (UUID) cur.get(0).get("id");
        if (cur.isEmpty())
            db.sql("insert into sales.daily_sales(id, store_id, sales_date, source, import_batch_id, created_by) values (?,?,?,?,?,?)")
                    .params(id, store, date, source, batch, u.id()).update();
        db.sql("""
                update sales.daily_sales set source = ?, import_batch_id = ?, gross_supply = ?, exempt_supply = ?, discount_amount = ?, refund_amount = ?,
                  vat_amount = ?, total_amount = ?, card_amount = ?, cash_amount = ?, other_pay_amount = ?, customer_count = ?, memo = ? where id = ?""")
                .params(source, batch, grossSupply, exempt, discSupply, refundSupply, vat, netTotal, card, cash, other, r.customers(), r.memo(), id).update();
        db.sql("delete from sales.daily_sales_division where daily_sales_id = ?").param(id).update();
        for (int i = 0; i < DIVS.size(); i++)
            db.sql("insert into sales.daily_sales_division(daily_sales_id, division_code, supply_amount, vat_amount) values (?,?,?,?)")
                    .params(id, DIVS.get(i), div[i].supply(), div[i].vat()).update();
        if (Boolean.TRUE.equals(r.close())) close(id, u);
        return id;
    }

    static long nz(Long v) { if (v != null && v < 0) throw ApiException.bad("금액은 0 이상이어야 합니다."); return v == null ? 0 : v; }

    /** 마감 → 결산 이벤트 */
    @Transactional
    public void close(UUID id, CurrentUser u) {
        var d = db.sql("select * from sales.daily_sales where id = ? for update").param(id).query().singleRow();
        scope.requireRoleOn(u, (UUID) d.get("store_id"), Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN);
        if ("CLOSED".equals(d.get("status"))) return;
        db.sql("update sales.daily_sales set status = 'CLOSED', closed_by = ?, closed_at = now() where id = ?").params(u.id(), id).update();
        trail.log("DAILY_SALES", id, "OPEN", "CLOSED", u.id(), "일 매출 마감");
        Map<String, Object> divs = new LinkedHashMap<>();
        for (var r : db.sql("select division_code, supply_amount, vat_amount from sales.daily_sales_division where daily_sales_id = ?").param(id).query().listOfRows())
            divs.put((String) r.get("division_code"), r.get("supply_amount"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dailySalesId", id); p.put("storeId", d.get("store_id")); p.put("salesDate", d.get("sales_date").toString());
        p.put("divisionSupply", divs); p.put("exemptSupply", d.get("exempt_supply")); p.put("discount", d.get("discount_amount"));
        p.put("refund", d.get("refund_amount")); p.put("vat", d.get("vat_amount")); p.put("total", d.get("total_amount"));
        p.put("card", d.get("card_amount")); p.put("cash", d.get("cash_amount")); p.put("otherPay", d.get("other_pay_amount"));
        outbox.publish("sales", "DAILY_SALES", id, "DailySalesClosed", p);
    }

    /** 총괄: 마감 취소 (결산 월 마감 전까지) → 결산에는 역분개 이벤트 */
    @Transactional
    public void reopen(UUID id, CurrentUser u) {
        u.require(Roles.HQ_ADMIN);
        var d = db.sql("select * from sales.daily_sales where id = ? for update").param(id).query().singleRow();
        if (!"CLOSED".equals(d.get("status"))) throw ApiException.conflict("마감된 매출이 아닙니다.");
        db.sql("update sales.daily_sales set status = 'OPEN', closed_at = null, closed_by = null where id = ?").param(id).update();
        trail.log("DAILY_SALES", id, "CLOSED", "OPEN", u.id(), "마감 취소");
        outbox.publish("sales", "DAILY_SALES", id, "DailySalesReopened",
                Map.of("dailySalesId", id, "storeId", d.get("store_id"), "salesDate", d.get("sales_date").toString()));
    }
}
