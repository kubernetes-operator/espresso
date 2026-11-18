package kr.espresso.core.logistics;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.common.Vat;
import kr.espresso.core.approval.ApprovalHandler;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.catalog.PriceService;
import kr.espresso.core.inventory.StockService;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 물류 요청 (요구 1, 2, 6, 7, 8, 19, 22) */
@Service
public class LogisticsService implements ApprovalHandler {
    public static final String DOC = "LOGISTICS_REQUEST";
    static final String[] STORE_ROLES = {Roles.OWNER, Roles.STORE_MGR, Roles.STAFF};
    static final String[] HQ_LOGI = {Roles.LOGISTICS_MGR, Roles.HQ_ADMIN};

    private final JdbcClient db;
    private final PriceService prices;
    private final StoreScope scope;
    private final DocNo docNo;
    private final DocTrail trail;
    private final ApprovalService approvals;
    private final StockService stock;
    private final Outbox outbox;
    private final Notifier notifier;

    public LogisticsService(JdbcClient db, PriceService prices, StoreScope scope, DocNo docNo, DocTrail trail,
                            ApprovalService approvals, StockService stock, Outbox outbox, Notifier notifier) {
        this.db = db; this.prices = prices; this.scope = scope; this.docNo = docNo; this.trail = trail;
        this.approvals = approvals; this.stock = stock; this.outbox = outbox; this.notifier = notifier;
    }

    public record LineReq(String sourceType, UUID itemId, BigDecimal qty, String memo,
                          String externalUrl, String itemName, String vendorName, UUID categoryId, String unit,
                          Long unitPrice, String priceInputMode, String taxType, Long shippingFee, String payer) {}
    public record SaveReq(UUID storeId, String title, String desiredDate, String memo, List<LineReq> lines, Boolean submit) {}

    // ------------------------------------------------------------------ 작성
    @Transactional
    public UUID create(SaveReq req, CurrentUser u) {
        scope.requireRoleOn(u, req.storeId(), Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.LOGISTICS_MGR, Roles.HQ_ADMIN);
        UUID id = UUID.randomUUID();
        db.sql("insert into logistics.request(id, request_no, store_id, title, desired_date, memo, created_by) values (?,?,?,?, cast(? as date), ?, ?)")
                .params(id, docNo.next("LR"), req.storeId(), req.title(), blank(req.desiredDate()), req.memo(), u.id()).update();
        trail.log(DOC, id, null, "DRAFT", u.id(), "작성");
        writeLines(id, req.storeId(), req.lines());
        if (Boolean.TRUE.equals(req.submit())) submit(id, u);
        return id;
    }

    @Transactional
    public void update(UUID id, SaveReq req, CurrentUser u) {
        var r = load(id);
        if (!"DRAFT".equals(r.get("status"))) throw ApiException.conflict("작성 중인 요청만 수정할 수 있습니다.");
        requireEditor(r, u);
        db.sql("update logistics.request set title = ?, desired_date = cast(? as date), memo = ?, updated_at = now() where id = ?")
                .params(req.title(), blank(req.desiredDate()), req.memo(), id).update();
        db.sql("delete from logistics.request_line where request_id = ?").param(id).update();
        writeLines(id, (UUID) r.get("store_id"), req.lines());
        if (Boolean.TRUE.equals(req.submit())) submit(id, u);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    private void writeLines(UUID reqId, UUID storeId, List<LineReq> lines) {
        if (lines == null || lines.isEmpty()) throw ApiException.bad("품목을 1개 이상 넣으세요.");
        int no = 1;
        for (LineReq l : lines) {
            if (l.qty() == null || l.qty().signum() <= 0) throw ApiException.bad("수량은 0보다 커야 합니다.");
            if ("HQ".equals(l.sourceType())) {
                var e = prices.effective(storeId, l.itemId());
                var c = PriceService.calc(e.listPrice(), l.qty(), e.discountType(), e.discountValue(), e.taxType());
                db.sql("""
                        insert into logistics.request_line(request_id, line_no, source_type, item_id, category_id, item_name, unit,
                          tax_type, qty, list_unit_price, discount_type, discount_value, discount_amount, supply_amount, vat_amount, total_amount, memo)
                        values (?,?, 'HQ', ?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                        .params(reqId, no++, e.itemId(), e.categoryId(), e.name(), e.unit(), e.taxType(), l.qty(), c.listUnitPrice(),
                                e.discountType(), e.discountValue(), c.discountAmount(), c.supply(), c.vat(), c.total(), l.memo()).update();
            } else if ("EXTERNAL".equals(l.sourceType())) {
                if (l.externalUrl() == null || !l.externalUrl().matches("(?i)^https?://.+")) throw ApiException.bad("외부 구매는 http(s) 주소가 필요합니다.");
                if (l.itemName() == null || l.itemName().isBlank()) throw ApiException.bad("외부 구매 품명을 입력하세요.");
                if (l.categoryId() == null) throw ApiException.bad("외부 구매 카테고리를 선택하세요.");
                if (l.unitPrice() == null || l.unitPrice() < 0) throw ApiException.bad("외부 구매 단가를 입력하세요.");
                String tax = Objects.requireNonNullElse(l.taxType(), Vat.TAXABLE);
                String mode = Objects.requireNonNullElse(l.priceInputMode(), "EXCLUSIVE");
                long gross = BigDecimal.valueOf(l.unitPrice()).multiply(l.qty()).setScale(0, RoundingMode.FLOOR).longValue();
                var a = "INCLUSIVE".equals(mode) ? Vat.fromTotal(gross, tax) : Vat.fromSupply(gross, tax);
                long unitSupply = BigDecimal.valueOf(a.supply()).divide(l.qty(), 0, RoundingMode.HALF_UP).longValue();
                db.sql("""
                        insert into logistics.request_line(request_id, line_no, source_type, category_id, item_name, unit, external_url, vendor_name,
                          payer, price_input_mode, tax_type, qty, list_unit_price, discount_amount, supply_amount, vat_amount, total_amount, shipping_fee, memo)
                        values (?,?, 'EXTERNAL', ?,?,?,?,?,?,?,?,?,?, 0, ?,?,?,?,?)""")
                        .params(reqId, no++, l.categoryId(), l.itemName().trim(), Objects.requireNonNullElse(l.unit(), "ea"), l.externalUrl().trim(),
                                l.vendorName(), Objects.requireNonNullElse(l.payer(), "STORE"), mode, tax, l.qty(), unitSupply,
                                a.supply(), a.vat(), a.total(), Objects.requireNonNullElse(l.shippingFee(), 0L), l.memo()).update();
            } else throw ApiException.bad("sourceType 은 HQ / EXTERNAL 입니다.");
        }
        recalc(reqId);
    }

    /** 합계: 과세 공급가 / 면세 공급가 / 세액 / 배송비(부가세 포함 금액으로 합계에만 더함) */
    void recalc(UUID reqId) {
        db.sql("""
                update logistics.request r set
                  taxable_supply = coalesce(t.taxable, 0), exempt_supply = coalesce(t.exempt, 0), vat_amount = coalesce(t.vat, 0),
                  shipping_fee = coalesce(t.ship, 0), total_amount = coalesce(t.total, 0) + coalesce(t.ship, 0), updated_at = now()
                from (select sum(supply_amount) filter (where tax_type = 'TAXABLE') taxable, sum(supply_amount) filter (where tax_type = 'EXEMPT') exempt,
                             sum(vat_amount) vat, sum(shipping_fee) ship, sum(total_amount) total
                      from logistics.request_line where request_id = ? and line_status <> 'CANCELLED') t
                where r.id = ?""").params(reqId, reqId).update();
    }

    // ------------------------------------------------------------------ 상신·결재 연동
    @Transactional
    public void submit(UUID id, CurrentUser u) {
        var r = load(id);
        if (!"DRAFT".equals(r.get("status"))) throw ApiException.conflict("작성 중인 요청만 상신할 수 있습니다.");
        requireEditor(r, u);
        db.sql("update logistics.request set status = 'REQUESTED', requested_by = ?, requested_at = now() where id = ?").params(u.id(), id).update();
        trail.log(DOC, id, "DRAFT", "REQUESTED", u.id(), "상신");
        approvals.start(DOC, id, (UUID) r.get("store_id"), ((Number) r.get("total_amount")).longValue(), u);
    }

    @Override public String docType() { return DOC; }
    @Override public String docLabel() { return "물류 요청"; }

    @Override
    public Map<String, Object> summary(UUID docId) {
        var r = db.sql("""
                select r.request_no, r.title, r.store_id, s.name as store_name, r.total_amount, r.status,
                  (select count(*) from logistics.request_line l where l.request_id = r.id) as line_count
                from logistics.request r join store.store s on s.id = r.store_id where r.id = ?""").param(docId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", r.get("request_no")); m.put("title", Objects.toString(r.get("title"), "") + " (" + r.get("line_count") + "개 품목)");
        m.put("storeId", r.get("store_id")); m.put("storeName", r.get("store_name"));
        m.put("amount", r.get("total_amount")); m.put("status", r.get("status")); m.put("link", "/logistics/" + docId);
        return m;
    }

    @Override
    public void onStepApproved(UUID docId, String role, UUID actor) {
        if (Roles.OWNER.equals(role) || Roles.STORE_MGR.equals(role)) {
            int n = db.sql("update logistics.request set status = 'STORE_APPROVED' where id = ? and status = 'REQUESTED'").param(docId).update();
            if (n > 0) trail.log(DOC, docId, "REQUESTED", "STORE_APPROVED", actor, "매장 결재 완료");
        }
    }

    @Override
    public void onApproved(UUID docId, UUID actor) {
        var r = load(docId);
        db.sql("update logistics.request set status = 'LOGISTICS_APPROVED', approved_at = now() where id = ?").param(docId).update();
        trail.log(DOC, docId, (String) r.get("status"), "LOGISTICS_APPROVED", actor, "최종 승인");
        notifier.send(List.of((UUID) r.get("created_by")), "LOGISTICS_APPROVED", "물류 요청 승인 완료",
                r.get("request_no") + " 출고 준비 단계로 넘어갑니다", "/logistics/" + docId, (UUID) r.get("store_id"));
    }

    @Override
    public void onRejected(UUID docId, UUID actor, String comment) {
        var r = load(docId);
        db.sql("update logistics.request set status = 'REJECTED', rejected_at = now() where id = ?").param(docId).update();
        trail.log(DOC, docId, (String) r.get("status"), "REJECTED", actor, comment);
    }

    @Override
    public void onWithdrawn(UUID docId, UUID actor) {
        var r = load(docId);
        db.sql("update logistics.request set status = 'DRAFT', requested_at = null where id = ?").param(docId).update();
        trail.log(DOC, docId, (String) r.get("status"), "DRAFT", actor, "요청자 회수");
    }

    // ------------------------------------------------------------------ 물류 처리
    @Transactional
    public void markPurchased(UUID id, List<UUID> lineIds, CurrentUser u) {
        var r = load(id);
        expect(r, "LOGISTICS_APPROVED", "PURCHASED");
        boolean logi = scope.hasRoleOn(u, (UUID) r.get("store_id"), HQ_LOGI);
        for (UUID lid : lineIds) {
            var l = db.sql("select source_type, payer from logistics.request_line where id = ? and request_id = ?").params(lid, id).query().singleRow();
            if (!"EXTERNAL".equals(l.get("source_type"))) throw ApiException.bad("외부 구매 품목만 구매 완료 처리합니다.");
            boolean storePays = "STORE".equals(l.get("payer")) && scope.hasRoleOn(u, (UUID) r.get("store_id"), Roles.OWNER, Roles.STORE_MGR);
            if (!logi && !storePays) throw ApiException.forbidden("구매 완료 처리 권한이 없습니다.");
            db.sql("update logistics.request_line set line_status = 'PURCHASED', purchased_at = now() where id = ?").param(lid).update();
        }
        Integer left = db.sql("select count(*) from logistics.request_line where request_id = ? and source_type = 'EXTERNAL' and line_status = 'OPEN'").param(id).query(Integer.class).single();
        if (left == 0 && "LOGISTICS_APPROVED".equals(r.get("status"))) {
            db.sql("update logistics.request set status = 'PURCHASED' where id = ?").param(id).update();
            trail.log(DOC, id, "LOGISTICS_APPROVED", "PURCHASED", u.id(), "외부 구매 완료");
        }
    }

    @Transactional
    public void prepare(UUID id, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), HQ_LOGI);
        expect(r, "LOGISTICS_APPROVED", "PURCHASED");
        requireExternalPurchased(id);
        db.sql("update logistics.request set status = 'PREPARING' where id = ?").param(id).update();
        trail.log(DOC, id, (String) r.get("status"), "PREPARING", u.id(), "출고 준비");
    }

    public record ShipReq(String carrier, String trackingNo, String memo) {}

    @Transactional
    public void ship(UUID id, ShipReq req, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), HQ_LOGI);
        expect(r, "LOGISTICS_APPROVED", "PURCHASED", "PREPARING");
        requireExternalPurchased(id);
        UUID sid = UUID.randomUUID();
        db.sql("insert into logistics.shipment(id, request_id, carrier, tracking_no, shipped_by, memo) values (?,?,?,?,?,?)")
                .params(sid, id, req.carrier(), req.trackingNo(), u.id(), req.memo()).update();
        db.sql("insert into logistics.shipment_line(shipment_id, request_line_id, shipped_qty) select ?, id, qty from logistics.request_line where request_id = ? and line_status <> 'CANCELLED'")
                .params(sid, id).update();
        db.sql("update logistics.request_line set line_status = 'SHIPPED' where request_id = ? and line_status in ('OPEN','PURCHASED')").param(id).update();
        db.sql("update logistics.request set status = 'SHIPPED', shipped_at = now() where id = ?").param(id).update();
        trail.log(DOC, id, (String) r.get("status"), "SHIPPED", u.id(),
                Objects.toString(req.carrier(), "") + " " + Objects.toString(req.trackingNo(), ""));
        notifier.send(storePeople((UUID) r.get("store_id")), "SHIPPED", "물류 발송", r.get("request_no") + " 발송되었습니다", "/logistics/" + id, (UUID) r.get("store_id"));
    }

    @Transactional
    public void deliver(UUID id, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), Roles.LOGISTICS_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF);
        expect(r, "SHIPPED");
        db.sql("update logistics.request set status = 'DELIVERED', delivered_at = now() where id = ?").param(id).update();
        db.sql("update logistics.shipment set delivered_at = now() where request_id = ? and delivered_at is null").param(id).update();
        trail.log(DOC, id, "SHIPPED", "DELIVERED", u.id(), "도착");
    }

    public record ReceiveLine(UUID lineId, BigDecimal receivedQty) {}
    public record ReceiveReq(List<ReceiveLine> lines, String memo) {}

    /** 매장 검수: 재고 입고(이동평균) + 부족분 '배송 부족' 결손 초안 + 결산 이벤트 */
    @Transactional
    public Map<String, Object> receive(UUID id, ReceiveReq req, CurrentUser u) {
        var r = load(id);
        UUID store = (UUID) r.get("store_id");
        scope.requireRoleOn(u, store, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.HQ_ADMIN);
        expect(r, "SHIPPED", "DELIVERED");
        Map<UUID, BigDecimal> got = new HashMap<>();
        if (req.lines() != null) req.lines().forEach(l -> got.put(l.lineId(), l.receivedQty()));
        var lines = db.sql("""
                select l.*, c.division_code, c.code as category_code,
                  (c.is_asset or coalesce(i.is_asset, false)
                     or (i.id is null and c.asset_threshold is not null and l.qty > 0 and l.supply_amount / l.qty >= c.asset_threshold)) as category_asset
                from logistics.request_line l join catalog.category c on c.id = l.category_id
                left join catalog.item i on i.id = l.item_id
                where l.request_id = ? and l.line_status <> 'CANCELLED' order by l.line_no""").param(id).query().listOfRows();
        List<Map<String, Object>> shortages = new ArrayList<>();
        List<Map<String, Object>> evLines = new ArrayList<>();
        for (var l : lines) {
            BigDecimal qty = (BigDecimal) l.get("qty");
            BigDecimal rec = got.getOrDefault((UUID) l.get("id"), qty);
            if (rec.signum() < 0 || rec.compareTo(qty) > 0) throw ApiException.bad("수령 수량은 0 ~ 요청 수량 사이여야 합니다: " + l.get("item_name"));
            boolean short_ = rec.compareTo(qty) < 0;
            db.sql("update logistics.request_line set received_qty = ?, line_status = ? where id = ?")
                    .params(rec, short_ ? "SHORT" : "RECEIVED", l.get("id")).update();
            long supply = ((Number) l.get("supply_amount")).longValue();
            boolean asset = Boolean.TRUE.equals(l.get("category_asset"));
            BigDecimal unitCost = BigDecimal.valueOf(supply).divide(qty, 2, RoundingMode.HALF_UP);
            if ("HQ".equals(l.get("source_type")) && !asset) {
                stock.receive(store, (UUID) l.get("item_id"), qty, unitCost, DOC, id, u.id());
                if (short_) shortages.add(Map.of("itemId", l.get("item_id"), "qty", qty.subtract(rec), "unitCost", unitCost, "name", l.get("item_name")));
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("lineId", l.get("id")); ev.put("itemName", l.get("item_name")); ev.put("sourceType", l.get("source_type"));
            ev.put("division", l.get("division_code")); ev.put("categoryCode", l.get("category_code")); ev.put("isAsset", asset);
            ev.put("stocked", "HQ".equals(l.get("source_type")) && !asset);
            ev.put("payer", l.get("payer")); ev.put("taxType", l.get("tax_type"));
            ev.put("supply", supply); ev.put("vat", l.get("vat_amount")); ev.put("total", l.get("total_amount"));
            ev.put("shippingFee", l.get("shipping_fee")); ev.put("qty", qty); ev.put("receivedQty", rec);
            evLines.add(ev);
        }
        UUID adjId = null;
        if (!shortages.isEmpty()) {
            adjId = UUID.randomUUID();
            String no = docNo.next("SA");
            long total = 0;
            db.sql("insert into inventory.stock_adjustment(id, adj_no, store_id, adj_type, status, source_type, source_id, memo, created_by) values (?,?,?, 'SHORTAGE', 'DRAFT', 'LOGISTICS_RECEIPT', ?, ?, ?)")
                    .params(adjId, no, store, id, r.get("request_no") + " 배송 부족분 (자동 생성)", u.id()).update();
            for (var s : shortages) {
                BigDecimal q = (BigDecimal) s.get("qty");
                BigDecimal uc = (BigDecimal) s.get("unitCost");
                long amt = q.multiply(uc).setScale(0, RoundingMode.HALF_UP).longValue();
                total += amt;
                db.sql("insert into inventory.stock_adjustment_line(adjustment_id, item_id, qty, reason_code, unit_cost, amount) values (?,?,?, 'DELIVERY_SHORT', ?, ?)")
                        .params(adjId, s.get("itemId"), q, uc, amt).update();
            }
            db.sql("update inventory.stock_adjustment set total_amount = ? where id = ?").params(total, adjId).update();
            trail.log("STOCK_ADJUSTMENT", adjId, null, "DRAFT", u.id(), "배송 부족 자동 생성: " + r.get("request_no"));
            trail.log(DOC, id, (String) r.get("status"), "PARTIALLY_RECEIVED", u.id(), "부족 " + shortages.size() + "건 → 결손 초안 " + no);
        }
        db.sql("update logistics.request set status = 'COMPLETED', received_at = now(), completed_at = now() where id = ?").param(id).update();
        trail.log(DOC, id, shortages.isEmpty() ? (String) r.get("status") : "PARTIALLY_RECEIVED", "COMPLETED", u.id(),
                Objects.requireNonNullElse(req.memo(), "검수 완료"));
        outbox.publish("logistics", DOC, id, "LogisticsReceived", Map.of(
                "requestId", id, "requestNo", r.get("request_no"), "storeId", store, "receivedAt", java.time.OffsetDateTime.now().toString(),
                "lines", evLines));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shortageAdjustmentId", adjId);
        return out;
    }

    @Transactional
    public void cancel(UUID id, CurrentUser u) {
        var r = load(id);
        boolean owner = u.id().equals(r.get("created_by")) || u.isHqAdmin();
        if (!owner) throw ApiException.forbidden("작성자 또는 총괄만 취소할 수 있습니다.");
        expect(r, "DRAFT", "REJECTED");
        db.sql("update logistics.request set status = 'CANCELLED', cancelled_at = now() where id = ?").param(id).update();
        trail.log(DOC, id, (String) r.get("status"), "CANCELLED", u.id(), "취소");
    }

    public record LinePriceReq(Long unitPrice, BigDecimal qty) {}

    /** 물류관리자 단가·수량 확정 (최종 승인 전) */
    @Transactional
    public void adjustLine(UUID id, UUID lineId, LinePriceReq req, CurrentUser u) {
        var r = load(id);
        scope.requireRoleOn(u, (UUID) r.get("store_id"), HQ_LOGI);
        expect(r, "REQUESTED", "STORE_APPROVED");
        var l = db.sql("select * from logistics.request_line where id = ? and request_id = ?").params(lineId, id).query().singleRow();
        BigDecimal qty = req.qty() != null ? req.qty() : (BigDecimal) l.get("qty");
        long unit = req.unitPrice() != null ? req.unitPrice() : ((Number) l.get("list_unit_price")).longValue();
        var c = PriceService.calc(unit, qty, (String) l.get("discount_type"), (BigDecimal) l.get("discount_value"), (String) l.get("tax_type"));
        db.sql("update logistics.request_line set qty = ?, list_unit_price = ?, discount_amount = ?, supply_amount = ?, vat_amount = ?, total_amount = ? where id = ?")
                .params(qty, unit, c.discountAmount(), c.supply(), c.vat(), c.total(), lineId).update();
        recalc(id);
        trail.log(DOC, id, (String) r.get("status"), (String) r.get("status"), u.id(),
                "단가·수량 조정: " + l.get("item_name") + " " + unit + "원 × " + qty.stripTrailingZeros().toPlainString());
    }

    // ------------------------------------------------------------------ 조회
    public Map<String, Object> load(UUID id) {
        var rows = db.sql("select * from logistics.request where id = ?").param(id).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("물류 요청");
        return rows.get(0);
    }

    private void expect(Map<String, Object> r, String... statuses) {
        if (!Arrays.asList(statuses).contains((String) r.get("status")))
            throw ApiException.conflict("현재 상태(" + r.get("status") + ")에서는 할 수 없는 작업입니다.");
    }

    private void requireEditor(Map<String, Object> r, CurrentUser u) {
        if (!u.id().equals(r.get("created_by")) && !scope.hasRoleOn(u, (UUID) r.get("store_id"), Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN))
            throw ApiException.forbidden("작성자 또는 매장 관리자만 수정·상신할 수 있습니다.");
    }

    private void requireExternalPurchased(UUID id) {
        Integer left = db.sql("select count(*) from logistics.request_line where request_id = ? and source_type = 'EXTERNAL' and line_status = 'OPEN'").param(id).query(Integer.class).single();
        if (left > 0) throw ApiException.conflict("외부 구매 품목 " + left + "건이 아직 구매 완료되지 않았습니다.");
    }

    private List<UUID> storePeople(UUID store) {
        return db.sql("select distinct created_by from logistics.request where store_id = ? and created_at > now() - interval '90 days'")
                .param(store).query(UUID.class).list();
    }
}
