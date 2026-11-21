package kr.espresso.core.sales;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 근무 마감 (2차 점검 반영): 모든 직원이 자기 근무의 판매·결제 금액을 입력해 마감 → 그날 소계.
 * 점주·매장 관리자가 소계를 일 매출로 반영(이후 일 매출 마감은 기존 흐름).
 * 금액은 POS 마감과 같은 결제 금액(VAT 포함) 기준. 결제 합계(카드+현금+기타)는 판매 합계(구분 합 − 할인 − 환불)와 같아야 함
 */
@RestController
@RequestMapping("/api/core/sales/shift-closes")
public class ShiftCloseController {
    static final String[] WRITE = {Roles.STAFF, Roles.STORE_MGR, Roles.OWNER, Roles.HQ_ADMIN};
    static final String[] APPLY = {Roles.STORE_MGR, Roles.OWNER, Roles.HQ_ADMIN};
    private final JdbcClient db;
    private final StoreScope scope;
    private final SalesService sales;

    public ShiftCloseController(JdbcClient db, StoreScope scope, SalesService sales) { this.db = db; this.scope = scope; this.sales = sales; }

    static long nz(Long v) { return v == null ? 0 : v; }

    @GetMapping
    public Map<String, Object> list(@RequestParam UUID storeId, @RequestParam(required = false) String date) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, WRITE);
        LocalDate d = date == null || date.isBlank() ? LocalDate.now(ZoneId.of("Asia/Seoul")) : LocalDate.parse(date);
        var rows = db.sql("select * from sales.shift_close where store_id = ? and sales_date = ? order by created_at").params(storeId, d).query().listOfRows();
        Map<String, Long> sum = new LinkedHashMap<>();
        for (String k : List.of("beverage", "dessert", "tableware", "etc", "discount", "refund", "card", "cash", "other_pay", "customers"))
            sum.put(k, rows.stream().mapToLong(r -> r.get(k) == null ? 0 : ((Number) r.get(k)).longValue()).sum());
        sum.put("net", sum.get("beverage") + sum.get("dessert") + sum.get("tableware") + sum.get("etc") - sum.get("discount") - sum.get("refund"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.toString()); m.put("items", rows); m.put("subtotal", sum);
        m.put("daily", db.sql("select id, status, total_amount from sales.daily_sales where store_id = ? and sales_date = ?").params(storeId, d).query().listOfRows().stream().findFirst().orElse(null));
        m.put("canApply", scope.hasRoleOn(u, storeId, APPLY));
        return m;
    }

    public record CloseReq(UUID storeId, String date, Long beverage, Long dessert, Long tableware, Long etc, Long discount, Long refund,
                           Long card, Long cash, Long otherPay, Integer customers, String memo) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody CloseReq r) {
        CurrentUser u = CurrentUser.get();
        if (r.storeId() == null) throw ApiException.bad("매장을 고르세요.");
        scope.requireRoleOn(u, r.storeId(), WRITE);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate d = r.date() == null || r.date().isBlank() ? today : LocalDate.parse(r.date());
        if (d.isAfter(today)) throw ApiException.bad("미래 날짜로 마감할 수 없습니다.");
        long net = nz(r.beverage()) + nz(r.dessert()) + nz(r.tableware()) + nz(r.etc()) - nz(r.discount()) - nz(r.refund());
        long pay = nz(r.card()) + nz(r.cash()) + nz(r.otherPay());
        if (net <= 0) throw ApiException.bad("판매 금액(음료·디저트·식기·포장·기타 중 하나 이상)을 입력하세요.");
        if (net != pay) throw ApiException.bad(String.format("결제 합계(카드+현금+기타 %,d원)가 판매 합계(%,d원)와 다릅니다. 차이 %,d원", pay, net, pay - net));
        var daily = db.sql("select status from sales.daily_sales where store_id = ? and sales_date = ?").params(r.storeId(), d).query(String.class).optional();
        if (daily.isPresent() && "CLOSED".equals(daily.get())) throw ApiException.conflict(d + " 일 매출이 이미 마감되었습니다.");
        UUID id = UUID.randomUUID();
        db.sql("""
                insert into sales.shift_close(id, store_id, sales_date, user_id, closer_name, beverage, dessert, tableware, etc, discount, refund, card, cash, other_pay, customers, memo)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(id, r.storeId(), d, u.id(), Objects.requireNonNullElse(u.name(), u.loginId()), nz(r.beverage()), nz(r.dessert()), nz(r.tableware()), nz(r.etc()),
                        nz(r.discount()), nz(r.refund()), nz(r.card()), nz(r.cash()), nz(r.otherPay()), r.customers(), r.memo()).update();
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var r = db.sql("select store_id, user_id, applied_at from sales.shift_close where id = ?").param(id).query().listOfRows();
        if (r.isEmpty()) throw ApiException.notFound("근무 마감");
        if (r.get(0).get("applied_at") != null) throw ApiException.conflict("일 매출에 반영된 마감은 지울 수 없습니다.");
        if (!u.id().equals(r.get(0).get("user_id"))) scope.requireRoleOn(u, (UUID) r.get(0).get("store_id"), APPLY);
        db.sql("delete from sales.shift_close where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    public record ApplyReq(UUID storeId, String date, Boolean close) {}

    /** 소계 → 일 매출 반영 (점주·매장 관리자) */
    @PostMapping("/apply")
    @Transactional
    public Map<String, Object> apply(@RequestBody ApplyReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), APPLY);
        LocalDate d = LocalDate.parse(r.date());
        @SuppressWarnings("unchecked")
        Map<String, Long> t = (Map<String, Long>) list(r.storeId(), r.date()).get("subtotal");
        if (t.get("net") <= 0) throw ApiException.bad("반영할 근무 마감이 없습니다.");
        UUID id = sales.upsert(r.storeId(), d, new SalesService.DayReq(t.get("beverage"), t.get("dessert"), t.get("tableware"), t.get("etc"), 0L,
                t.get("discount"), t.get("refund"), t.get("card"), t.get("cash"), t.get("other_pay"), t.get("customers").intValue(), "근무 마감 소계 반영",
                Boolean.TRUE.equals(r.close())), "MANUAL", null, u);
        db.sql("update sales.shift_close set applied_at = now() where store_id = ? and sales_date = ? and applied_at is null").params(r.storeId(), d).update();
        return Map.of("id", id);
    }
}
