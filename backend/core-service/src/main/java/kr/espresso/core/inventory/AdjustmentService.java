package kr.espresso.core.inventory;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.core.approval.ApprovalHandler;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 손실·결손·내부사용 (요구 10): 앱 등록 → 순차 결재 → 최종 승인 시 재고 차감 + 결산 이벤트 */
@Service
public class AdjustmentService implements ApprovalHandler {
    public static final String DOC = "STOCK_ADJUSTMENT";
    static final Map<String, String> TYPE_LABEL = Map.of("LOSS", "손실", "SHORTAGE", "결손", "INTERNAL_USE", "내부사용");

    private final JdbcClient db;
    private final StoreScope scope;
    private final DocNo docNo;
    private final DocTrail trail;
    private final ApprovalService approvals;
    private final StockService stock;
    private final Outbox outbox;

    public AdjustmentService(JdbcClient db, StoreScope scope, DocNo docNo, DocTrail trail, ApprovalService approvals, StockService stock, Outbox outbox) {
        this.db = db; this.scope = scope; this.docNo = docNo; this.trail = trail; this.approvals = approvals; this.stock = stock; this.outbox = outbox;
    }

    public record LineReq(UUID itemId, BigDecimal qty, String reasonCode, String memo) {}
    public record SaveReq(UUID storeId, String adjType, String memo, List<LineReq> lines, Boolean submit) {}

    @Transactional
    public UUID create(SaveReq req, CurrentUser u) {
        requireCreator(u, req.storeId(), req.adjType());
        UUID id = UUID.randomUUID();
        db.sql("insert into inventory.stock_adjustment(id, adj_no, store_id, adj_type, source_type, memo, created_by) values (?,?,?,?, 'MANUAL', ?, ?)")
                .params(id, docNo.next("SA"), req.storeId(), req.adjType(), req.memo(), u.id()).update();
        trail.log(DOC, id, null, "DRAFT", u.id(), "작성");
        writeLines(id, req.storeId(), req.adjType(), req.lines());
        if (Boolean.TRUE.equals(req.submit())) submit(id, u);
        return id;
    }

    @Transactional
    public void update(UUID id, SaveReq req, CurrentUser u) {
        var a = load(id);
        if (!"DRAFT".equals(a.get("status"))) throw ApiException.conflict("작성 중인 문서만 수정할 수 있습니다.");
        requireCreator(u, (UUID) a.get("store_id"), (String) a.get("adj_type"));
        db.sql("update inventory.stock_adjustment set memo = ? where id = ?").params(req.memo(), id).update();
        if (req.lines() != null) {
            db.sql("delete from inventory.stock_adjustment_line where adjustment_id = ?").param(id).update();
            writeLines(id, (UUID) a.get("store_id"), (String) a.get("adj_type"), req.lines());
        }
        if (Boolean.TRUE.equals(req.submit())) submit(id, u);
    }

    private void requireCreator(CurrentUser u, UUID store, String type) {
        if (!TYPE_LABEL.containsKey(type)) throw ApiException.bad("유형은 LOSS(손실) / SHORTAGE(결손) / INTERNAL_USE(내부사용) 입니다.");
        if (scope.hasRoleOn(u, store, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.HQ_ADMIN)) return;
        if ("SHORTAGE".equals(type) && scope.hasRoleOn(u, store, Roles.LOGISTICS_MGR)) return;   // 물류: 배송 결손
        throw ApiException.forbidden("이 매장의 손실·결손을 등록할 권한이 없습니다.");
    }

    private void writeLines(UUID id, UUID store, String type, List<LineReq> lines) {
        if (lines == null || lines.isEmpty()) throw ApiException.bad("품목을 1개 이상 넣으세요.");
        long total = 0;
        for (LineReq l : lines) {
            if (l.qty() == null || l.qty().signum() <= 0) throw ApiException.bad("수량은 0보다 커야 합니다.");
            String rt = db.sql("select adj_type from inventory.reason_code where code = ? and is_active").param(l.reasonCode()).query(String.class)
                    .optional().orElseThrow(() -> ApiException.bad("사유 코드를 확인하세요: " + l.reasonCode()));
            if (!rt.equals(type)) throw ApiException.bad("사유 '" + l.reasonCode() + "'는 " + TYPE_LABEL.get(rt) + " 사유입니다.");
            BigDecimal cost = db.sql("select avg_cost from inventory.stock_balance where store_id = ? and item_id = ?").params(store, l.itemId())
                    .query(BigDecimal.class).optional().orElseGet(() -> db.sql("select cast(base_price as numeric) from catalog.item where id = ?").param(l.itemId()).query(BigDecimal.class).single());
            long amt = l.qty().multiply(cost).setScale(0, RoundingMode.HALF_UP).longValue();
            total += amt;
            db.sql("insert into inventory.stock_adjustment_line(adjustment_id, item_id, qty, reason_code, unit_cost, amount, memo) values (?,?,?,?,?,?,?)")
                    .params(id, l.itemId(), l.qty(), l.reasonCode(), cost, amt, l.memo()).update();
        }
        db.sql("update inventory.stock_adjustment set total_amount = ? where id = ?").params(total, id).update();
    }

    @Transactional
    public void submit(UUID id, CurrentUser u) {
        var a = load(id);
        if (!"DRAFT".equals(a.get("status"))) throw ApiException.conflict("작성 중인 문서만 상신할 수 있습니다.");
        requireCreator(u, (UUID) a.get("store_id"), (String) a.get("adj_type"));
        Integer n = db.sql("select count(*) from inventory.stock_adjustment_line where adjustment_id = ?").param(id).query(Integer.class).single();
        if (n == 0) throw ApiException.bad("품목이 없습니다.");
        db.sql("update inventory.stock_adjustment set status = 'REQUESTED', requested_by = ?, requested_at = now() where id = ?").params(u.id(), id).update();
        trail.log(DOC, id, "DRAFT", "REQUESTED", u.id(), "상신");
        approvals.start(DOC, id, (UUID) a.get("store_id"), ((Number) a.get("total_amount")).longValue(), u);
    }

    @Transactional
    public void cancel(UUID id, CurrentUser u) {
        var a = load(id);
        if (!List.of("DRAFT", "REJECTED").contains((String) a.get("status"))) throw ApiException.conflict("작성 중이거나 반려된 문서만 취소할 수 있습니다.");
        if (!u.id().equals(a.get("created_by")) && !scope.hasRoleOn(u, (UUID) a.get("store_id"), Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN))
            throw ApiException.forbidden("작성자 또는 매장 관리자만 취소할 수 있습니다.");
        db.sql("update inventory.stock_adjustment set status = 'CANCELLED' where id = ?").param(id).update();
        trail.log(DOC, id, (String) a.get("status"), "CANCELLED", u.id(), "취소");
    }

    public Map<String, Object> load(UUID id) {
        var rows = db.sql("select * from inventory.stock_adjustment where id = ?").param(id).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("손실·결손 문서");
        return rows.get(0);
    }

    // ---------------- 결재 연동 ----------------
    @Override public String docType() { return DOC; }
    @Override public String docLabel() { return "손실·결손·사용"; }

    @Override
    public Map<String, Object> summary(UUID docId) {
        var a = db.sql("""
                select a.adj_no, a.adj_type, a.store_id, s.name store_name, a.total_amount, a.status,
                  (select string_agg(distinct rc.name, ', ') from inventory.stock_adjustment_line l join inventory.reason_code rc on rc.code = l.reason_code where l.adjustment_id = a.id) reasons,
                  (select count(*) from inventory.stock_adjustment_line l where l.adjustment_id = a.id) cnt
                from inventory.stock_adjustment a join store.store s on s.id = a.store_id where a.id = ?""").param(docId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", a.get("adj_no"));
        m.put("title", TYPE_LABEL.get(a.get("adj_type")) + " · " + Objects.toString(a.get("reasons"), "") + " (" + a.get("cnt") + "개 품목)");
        m.put("storeId", a.get("store_id")); m.put("storeName", a.get("store_name"));
        m.put("amount", a.get("total_amount")); m.put("status", a.get("status")); m.put("link", "/adjustments/" + docId);
        return m;
    }

    @Override
    public void onApproved(UUID docId, UUID actor) {
        var a = load(docId);
        UUID store = (UUID) a.get("store_id");
        String type = (String) a.get("adj_type");
        List<Map<String, Object>> ev = new ArrayList<>();
        long total = 0;
        for (var l : db.sql("""
                select l.*, rc.account_hint, rc.name reason_name, i.name item_name, c.division_code
                from inventory.stock_adjustment_line l join inventory.reason_code rc on rc.code = l.reason_code
                join catalog.item i on i.id = l.item_id join catalog.category c on c.id = i.category_id
                where l.adjustment_id = ?""").param(docId).query().listOfRows()) {
            long amt = stock.issue(store, (UUID) l.get("item_id"), (BigDecimal) l.get("qty"), type, DOC, docId, actor);
            db.sql("update inventory.stock_adjustment_line set amount = ? where id = ?").params(amt, l.get("id")).update();
            total += amt;
            ev.add(Map.of("itemName", l.get("item_name"), "reasonCode", l.get("reason_code"), "accountHint", l.get("account_hint"),
                    "division", l.get("division_code"), "qty", l.get("qty"), "amount", amt));
        }
        db.sql("update inventory.stock_adjustment set status = 'APPROVED', approved_at = now(), completed_at = now(), total_amount = ? where id = ?")
                .params(total, docId).update();
        trail.log(DOC, docId, "REQUESTED", "APPROVED", actor, "최종 승인 · 재고 차감 " + total + "원");
        outbox.publish("inventory", DOC, docId, "StockAdjustmentApproved", Map.of(
                "adjustmentId", docId, "adjNo", a.get("adj_no"), "storeId", store, "adjType", type,
                "approvedAt", java.time.OffsetDateTime.now().toString(), "lines", ev));
    }

    @Override
    public void onRejected(UUID docId, UUID actor, String comment) {
        db.sql("update inventory.stock_adjustment set status = 'REJECTED', rejected_at = now() where id = ?").param(docId).update();
        trail.log(DOC, docId, "REQUESTED", "REJECTED", actor, comment);
    }

    @Override
    public void onWithdrawn(UUID docId, UUID actor) {
        db.sql("update inventory.stock_adjustment set status = 'DRAFT', requested_at = null where id = ?").param(docId).update();
        trail.log(DOC, docId, "REQUESTED", "DRAFT", actor, "요청자 회수");
    }
}
