package kr.espresso.settlement.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.settlement.ledger.Ledger.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * core → settlement 이벤트 수신 (멱등). 결산 DB 는 이벤트로만 채워진다 (설계 §4.1)
 * 계정 매핑은 docs/ERD.md §7
 */
@RestController
public class EventController {
    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    static final Map<String, String> DIV_SALES = Map.of("BEVERAGE", "401", "DESSERT", "402", "TABLEWARE", "406", "MANAGEMENT", "403");
    /** 식기·포장·관리 구분은 소진하면 소모품비(내부사용), 음료·디저트는 매출원가 */
    static boolean supplies(String div) { return "TABLEWARE".equals(div) || "MANAGEMENT".equals(div); }
    static final Map<String, String> HINT = Map.of("DISPOSAL", "861", "SHRINKAGE", "862", "TASTING", "863", "SUPPLIES", "830");

    private final JdbcClient db;
    private final Ledger ledger;
    private final ObjectMapper om;

    public EventController(JdbcClient db, Ledger ledger, ObjectMapper om) { this.db = db; this.ledger = ledger; this.om = om; }

    public record Event(UUID id, String type, String payload, String createdAt) {}

    @PostMapping("/internal/events")
    @Transactional
    public Map<String, Object> receive(@RequestBody Event ev) throws Exception {
        int n = db.sql("insert into stl.inbound_event(event_id, event_type) values (?,?) on conflict do nothing").params(ev.id(), ev.type()).update();
        if (n == 0) return Map.of("duplicate", true);
        JsonNode p = om.readTree(ev.payload());
        switch (ev.type()) {
            case "LogisticsReceived" -> logisticsReceived(p);
            case "StockAdjustmentApproved" -> stockAdjusted(p);
            case "StockConsumed" -> stockConsumed(p);
            case "DailySalesClosed" -> salesClosed(p);
            case "DailySalesReopened" -> ledger.reverse("DAILY_SALES", uuid(p, "dailySalesId"), "매출 마감 취소 " + p.path("salesDate").asText(), null);
            case "PayrollConfirmed" -> payrollConfirmed(p);
            default -> log.warn("unknown event {}", ev.type());
        }
        db.sql("update stl.inbound_event set processed_at = now() where event_id = ?").param(ev.id()).update();
        return Map.of("ok", true);
    }

    static UUID uuid(JsonNode p, String f) { return UUID.fromString(p.path(f).asText()); }
    static LocalDate date(JsonNode p, String f) {
        String s = p.path(f).asText();
        return s.length() > 10 ? OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate() : LocalDate.parse(s);
    }

    /** 물류 입고: 재고/설비/직접비 + 매입세액 / 본사 미지급·매장 지급 */
    void logisticsReceived(JsonNode p) {
        UUID store = uuid(p, "storeId"), reqId = uuid(p, "requestId");
        LocalDate d = date(p, "receivedAt");
        String no = p.path("requestNo").asText();
        List<Line> lines = new ArrayList<>();
        Map<String, long[]> charges = new LinkedHashMap<>();   // type -> [supply, vat, total]
        for (JsonNode l : p.path("lines")) {
            long supply = l.path("supply").asLong(), vat = l.path("vat").asLong(), ship = l.path("shippingFee").asLong();
            String div = l.path("division").asText(null), name = l.path("itemName").asText();
            String debitAcc;
            if (l.path("stocked").asBoolean()) debitAcc = "146";
            else if (l.path("isAsset").asBoolean()) {
                debitAcc = "206";
                db.sql("insert into stl.fixed_asset(store_id, name, category_code, division_code, acquired_date, cost, useful_life_months, source_type, source_id) values (?,?,?,?,?,?, 60, 'LOGISTICS_REQUEST', ?)")
                        .params(store, name, l.path("categoryCode").asText(), div, d, supply, reqId).update();
            } else debitAcc = supplies(div) ? "830" : "451";
            lines.add(Line.dr(debitAcc, supply, div, name));
            if (vat > 0) lines.add(Line.dr("135", vat, div, name));
            if (ship > 0) lines.add(Line.dr("824", ship, div, name + " 배송비"));
            String credit; String chargeType = null;
            if ("HQ".equals(l.path("sourceType").asText())) { credit = "253"; chargeType = "HQ_SUPPLY"; }
            else if ("HQ".equals(l.path("payer").asText())) { credit = "253"; chargeType = "HQ_PAID_PURCHASE"; }
            else credit = "103";
            lines.add(Line.cr(credit, supply + vat + ship, div, name));
            if (chargeType != null) {
                long[] c = charges.computeIfAbsent(chargeType, k -> new long[3]);
                c[0] += supply + ship; c[1] += vat; c[2] += supply + vat + ship;
            }
        }
        ledger.post(store, d, "LOGISTICS_RECEIPT", reqId, "물류 입고 " + no, lines, null);
        for (var c : charges.entrySet())
            db.sql("insert into stl.hq_charge(store_id, period, charge_type, description, ref_type, ref_id, tax_type, supply_amount, vat_amount, total_amount) values (?,?,?,?, 'LOGISTICS_REQUEST', ?, 'TAXABLE', ?,?,?)")
                    .params(store, Ledger.period(d), c.getKey(), (c.getKey().equals("HQ_SUPPLY") ? "본사 물류 " : "본사 대납 외부구매 ") + no,
                            reqId, c.getValue()[0], c.getValue()[1], c.getValue()[2]).update();
    }

    void stockAdjusted(JsonNode p) {
        UUID store = uuid(p, "storeId");
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : p.path("lines")) {
            long amt = l.path("amount").asLong();
            String div = l.path("division").asText(null);
            lines.add(Line.dr(HINT.getOrDefault(l.path("accountHint").asText(), "862"), amt, div, l.path("itemName").asText()));
            lines.add(Line.cr("146", amt, div, l.path("itemName").asText()));
        }
        ledger.post(store, date(p, "approvedAt"), "STOCK_ADJ", uuid(p, "adjustmentId"), "손실·결손·사용 " + p.path("adjNo").asText(), lines, null);
    }

    /** 실사 기준 판매 소진 → 음료·디저트는 매출원가, 식기·포장·관리는 소모품비 */
    void stockConsumed(JsonNode p) {
        UUID store = uuid(p, "storeId");
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : p.path("lines")) {
            long amt = l.path("amount").asLong();
            String div = l.path("division").asText(null);
            lines.add(Line.dr(supplies(div) ? "830" : "451", amt, div, l.path("itemName").asText()));
            lines.add(Line.cr("146", amt, div, l.path("itemName").asText()));
        }
        ledger.post(store, date(p, "countedAt"), "STOCK_USAGE", uuid(p, "countId"), "실사 기준 판매 소진", lines, null);
    }

    void salesClosed(JsonNode p) {
        UUID store = uuid(p, "storeId");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.dr("108", p.path("card").asLong(), null, "카드"));
        lines.add(Line.dr("101", p.path("cash").asLong(), null, "현금"));
        lines.add(Line.dr("103", p.path("otherPay").asLong(), null, "기타 결제"));
        lines.add(Line.dr("404", p.path("discount").asLong(), null, "매출할인"));
        lines.add(Line.dr("405", p.path("refund").asLong(), null, "환불"));
        p.path("divisionSupply").fields().forEachRemaining(e -> lines.add(Line.cr(DIV_SALES.get(e.getKey()), e.getValue().asLong(), e.getKey(), "매출")));
        lines.add(Line.cr("403", p.path("exemptSupply").asLong(), "MANAGEMENT", "면세 매출"));
        lines.add(Line.cr("255", p.path("vat").asLong(), null, "매출세액"));
        ledger.post(store, date(p, "salesDate"), "DAILY_SALES", uuid(p, "dailySalesId"), "일 매출 " + p.path("salesDate").asText(), lines, null);
    }

    /** 급여 확정: 급여·잡급·제수당, 사업자 부담분, 퇴직급여 충당 / 예수금·미지급 */
    void payrollConfirmed(JsonNode p) {
        UUID store = uuid(p, "storeId");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.dr("801", p.path("regularPay").asLong(), null, "급여"));
        lines.add(Line.dr("804", p.path("partTimePay").asLong(), null, "잡급"));
        lines.add(Line.dr("803", p.path("allowances").asLong(), null, "제수당"));
        lines.add(Line.dr("811", p.path("employerContrib").asLong(), null, "4대보험 사업자분"));
        lines.add(Line.dr("806", p.path("severanceAccrual").asLong(), null, "퇴직급여 충당"));
        lines.add(Line.cr("254", p.path("deductions").asLong() + p.path("employerContrib").asLong(), null, "예수금(공제+사업자분)"));
        lines.add(Line.cr("262", p.path("netPay").asLong(), null, "미지급 급여"));
        lines.add(Line.cr("295", p.path("severanceAccrual").asLong(), null, "퇴직급여충당부채"));
        String period = p.path("period").asText();
        LocalDate d = LocalDate.of(Integer.parseInt(period.substring(0, 4)), Integer.parseInt(period.substring(4)), 1).plusMonths(1).minusDays(1);
        ledger.post(store, d, "PAYROLL", uuid(p, "payrollRunId"), "급여 " + period, lines, null);
    }
}
