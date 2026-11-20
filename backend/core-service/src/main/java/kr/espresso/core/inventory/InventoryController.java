package kr.espresso.core.inventory;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.support.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@RestController
@RequestMapping("/api/core/inventory")
public class InventoryController {
    private final JdbcClient db;
    private final StoreScope scope;
    private final AdjustmentService adj;
    private final ApprovalService approvals;
    private final DocTrail trail;
    private final DocNo docNo;
    private final StockService stock;
    private final Outbox outbox;

    public InventoryController(JdbcClient db, StoreScope scope, AdjustmentService adj, ApprovalService approvals, DocTrail trail, DocNo docNo, StockService stock, Outbox outbox) {
        this.outbox = outbox;
        this.db = db; this.scope = scope; this.adj = adj; this.approvals = approvals; this.trail = trail; this.docNo = docNo; this.stock = stock;
    }

    private Set<UUID> stores(CurrentUser u, UUID storeId) {
        if (storeId != null) { scope.check(u, storeId); return Set.of(storeId); }
        return scope.allowed(u);
    }

    /** 재고 현황 (매장별 또는 합산) */
    @GetMapping
    public List<Map<String, Object>> balances(@RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = stores(u, storeId);
        if (st.isEmpty()) return List.of();
        return db.sql("""
                select b.store_id, s.name store_name, b.item_id, i.sku, i.name item_name, i.unit, c.name category_name, c.division_code,
                       b.qty, b.avg_cost, round(b.qty * b.avg_cost) as stock_value, b.safety_qty, b.updated_at,
                       (b.safety_qty is not null and b.qty < b.safety_qty) as low
                from inventory.stock_balance b join catalog.item i on i.id = b.item_id
                join catalog.category c on c.id = i.category_id join store.store s on s.id = b.store_id
                where b.store_id in (:st) order by s.code, c.sort_order, i.name""").param("st", st).query().listOfRows();
    }

    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger(@RequestParam UUID storeId, @RequestParam(required = false) UUID itemId) {
        CurrentUser u = CurrentUser.get();
        scope.check(u, storeId);
        return db.sql("""
                select l.occurred_at, l.item_id, i.name item_name, l.qty_delta, l.unit_cost, l.amount, l.reason, l.ref_type, l.ref_id, l.balance_qty, l.actor_id
                from inventory.stock_ledger l join catalog.item i on i.id = l.item_id
                where l.store_id = ? and (cast(? as uuid) is null or l.item_id = ?) order by l.occurred_at desc limit 200""")
                .params(storeId, itemId, itemId).query().listOfRows();
    }

    public record SafetyReq(UUID storeId, UUID itemId, BigDecimal safetyQty) {}

    @PutMapping("/safety")
    public Map<String, Object> safety(@RequestBody SafetyReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        db.sql("update inventory.stock_balance set safety_qty = ? where store_id = ? and item_id = ?").params(r.safetyQty(), r.storeId(), r.itemId()).update();
        return Map.of("ok", true);
    }

    public record HqReceiptLine(UUID itemId, BigDecimal qty, BigDecimal unitCost) {}
    public record HqReceiptReq(List<HqReceiptLine> lines, String memo) {}

    /** 본사 창고 입고 (공급처 매입): 본사 재고 증가 · 이동평균. 매장 발송 시 본사 재고에서 차감된다 */
    @PostMapping("/hq-receipts")
    @Transactional
    public Map<String, Object> hqReceipt(@RequestBody HqReceiptReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR);
        if (r.lines() == null || r.lines().isEmpty()) throw ApiException.bad("입고할 품목을 넣으세요.");
        UUID ref = UUID.randomUUID();
        int n = 0;
        for (HqReceiptLine l : r.lines()) {
            if (l.itemId() == null || l.qty() == null || l.qty().signum() <= 0) throw ApiException.bad("품목과 수량을 확인하세요.");
            BigDecimal cost = l.unitCost();
            if (cost == null) cost = db.sql("select coalesce(hq_cost, base_price) from catalog.item where id = ?").param(l.itemId()).query(BigDecimal.class).single();
            stock.receive(kr.espresso.common.Sites.HQ, l.itemId(), l.qty(), cost, "HQ_RECEIPT", ref, u.id());
            n++;
        }
        trail.log("HQ_RECEIPT", ref, null, "DONE", u.id(), Objects.requireNonNullElse(r.memo(), "본사 입고 " + n + "건"));
        return Map.of("id", ref, "lines", n);
    }

    @GetMapping("/reasons")
    public List<Map<String, Object>> reasons() {
        CurrentUser.get();
        return db.sql("select code, adj_type, name from inventory.reason_code where is_active order by adj_type, code").query().listOfRows();
    }

    // ---------------- 손실·결손·내부사용 ----------------
    @GetMapping("/adjustments")
    public List<Map<String, Object>> adjustments(@RequestParam(required = false) UUID storeId, @RequestParam(required = false) String status) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = stores(u, storeId);
        if (st.isEmpty()) return List.of();
        return db.sql("""
                select a.id, a.adj_no, a.store_id, s.name store_name, a.adj_type, a.status, a.source_type, a.total_amount, a.memo,
                       a.created_by, a.created_at, a.requested_at, a.approved_at, a.completed_at,
                       (select string_agg(distinct rc.name, ', ') from inventory.stock_adjustment_line l join inventory.reason_code rc on rc.code = l.reason_code where l.adjustment_id = a.id) reasons,
                       (select count(*) from inventory.stock_adjustment_line l where l.adjustment_id = a.id) line_count
                from inventory.stock_adjustment a join store.store s on s.id = a.store_id
                where a.store_id in (:st) and (cast(:status as varchar) is null or a.status = cast(:status as varchar))
                order by a.created_at desc limit 300""").param("st", st).param("status", status).query().listOfRows();
    }

    @GetMapping("/adjustments/{id}")
    public Map<String, Object> adjustment(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        Map<String, Object> a = new LinkedHashMap<>(adj.load(id));
        scope.check(u, (UUID) a.get("store_id"));
        a.put("store_name", db.sql("select name from store.store where id = ?").param(a.get("store_id")).query(String.class).single());
        a.put("lines", db.sql("""
                select l.*, i.name item_name, i.sku, i.unit, rc.name reason_name from inventory.stock_adjustment_line l
                join catalog.item i on i.id = l.item_id join inventory.reason_code rc on rc.code = l.reason_code where l.adjustment_id = ?""").param(id).query().listOfRows());
        a.put("approval", approvals.forDoc(AdjustmentService.DOC, id));
        a.put("history", trail.history(AdjustmentService.DOC, id));
        return a;
    }

    @PostMapping("/adjustments")
    public Map<String, Object> create(@RequestBody AdjustmentService.SaveReq req) { return Map.of("id", adj.create(req, CurrentUser.get())); }

    @PutMapping("/adjustments/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody AdjustmentService.SaveReq req) { adj.update(id, req, CurrentUser.get()); return Map.of("id", id); }

    @PostMapping("/adjustments/{id}/submit")
    public Map<String, Object> submit(@PathVariable UUID id) { adj.submit(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/adjustments/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) { adj.cancel(id, CurrentUser.get()); return Map.of("ok", true); }

    // ---------------- 재고 실사 ----------------
    @GetMapping("/counts")
    public List<Map<String, Object>> counts(@RequestParam UUID storeId) {
        CurrentUser u = CurrentUser.get();
        scope.check(u, storeId);
        return db.sql("""
                select c.*, a.adj_no, (select count(*) from inventory.stock_count_line l where l.count_id = c.id) line_count
                from inventory.stock_count c left join inventory.stock_adjustment a on a.id = c.adjustment_id
                where c.store_id = ? order by c.started_at desc limit 50""").param(storeId).query().listOfRows();
    }

    /** 실사 시작: 현재 장부 수량 스냅샷 */
    @PostMapping("/counts")
    @Transactional
    public Map<String, Object> startCount(@RequestBody Map<String, UUID> body) {
        CurrentUser u = CurrentUser.get();
        UUID store = body.get("storeId");
        scope.requireRoleOn(u, store, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.HQ_ADMIN);
        Integer open = db.sql("select count(*) from inventory.stock_count where store_id = ? and status = 'IN_PROGRESS'").param(store).query(Integer.class).single();
        if (open > 0) throw ApiException.conflict("진행 중인 실사가 있습니다. 먼저 제출하세요.");
        UUID id = UUID.randomUUID();
        db.sql("insert into inventory.stock_count(id, store_id, started_by) values (?,?,?)").params(id, store, u.id()).update();
        db.sql("insert into inventory.stock_count_line(count_id, item_id, system_qty, counted_qty) select ?, item_id, qty, qty from inventory.stock_balance where store_id = ?")
                .params(id, store).update();
        return Map.of("id", id);
    }

    @GetMapping("/counts/{id}")
    public Map<String, Object> count(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var c = db.sql("select * from inventory.stock_count where id = ?").param(id).query().singleRow();
        scope.check(u, (UUID) c.get("store_id"));
        Map<String, Object> m = new LinkedHashMap<>(c);
        m.put("lines", db.sql("""
                select l.*, i.name item_name, i.sku, i.unit, i.barcode, cat.name category_name from inventory.stock_count_line l
                join catalog.item i on i.id = l.item_id join catalog.category cat on cat.id = i.category_id
                where l.count_id = ? order by cat.sort_order, i.name""").param(id).query().listOfRows());
        return m;
    }

    public record CountLine(UUID itemId, BigDecimal countedQty) {}
    public record CountSubmit(List<CountLine> lines, String shortageMode) {}

    /**
     * 실사 제출. 부족분 처리 방식(shortageMode):
     *  USAGE(기본) = 판매 소진 → 매출원가 (레시피 차감이 없는 기간의 실사법 원가)
     *  SHORTAGE   = 결손(실사 차이) 초안 → 결재
     * 초과분은 즉시 실사 조정 입고
     */
    @PostMapping("/counts/{id}/submit")
    @Transactional
    public Map<String, Object> submitCount(@PathVariable UUID id, @RequestBody CountSubmit body) {
        List<CountLine> lines = body.lines() == null ? List.of() : body.lines();
        boolean usage = !"SHORTAGE".equals(body.shortageMode());
        CurrentUser u = CurrentUser.get();
        var c = db.sql("select * from inventory.stock_count where id = ? for update").param(id).query().singleRow();
        UUID store = (UUID) c.get("store_id");
        scope.requireRoleOn(u, store, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.HQ_ADMIN);
        if (!"IN_PROGRESS".equals(c.get("status"))) throw ApiException.conflict("이미 제출한 실사입니다.");
        for (CountLine l : lines)
            db.sql("update inventory.stock_count_line set counted_qty = ? where count_id = ? and item_id = ?").params(l.countedQty(), id, l.itemId()).update();
        var diffs = db.sql("select item_id, system_qty, counted_qty from inventory.stock_count_line where count_id = ? and counted_qty <> system_qty").param(id).query().listOfRows();
        UUID adjId = null; int surplus = 0;
        List<AdjustmentService.LineReq> shortLines = new ArrayList<>();
        for (var d : diffs) {
            BigDecimal diff = ((BigDecimal) d.get("counted_qty")).subtract((BigDecimal) d.get("system_qty"));
            if (diff.signum() < 0) shortLines.add(new AdjustmentService.LineReq((UUID) d.get("item_id"), diff.negate(), "COUNT_DIFF", "실사 차이"));
            else {
                BigDecimal cost = stock.balance(store, (UUID) d.get("item_id"))[1];
                stock.receive(store, (UUID) d.get("item_id"), diff, cost, "STOCK_COUNT", id, u.id());
                surplus++;
            }
        }
        if (!shortLines.isEmpty() && usage) {
            List<Map<String, Object>> ev = new ArrayList<>();
            for (var l : shortLines) {
                var info = db.sql("select i.name, c.division_code from catalog.item i join catalog.category c on c.id = i.category_id where i.id = ?").param(l.itemId()).query().singleRow();
                long amt = stock.issue(store, l.itemId(), l.qty(), "SALE", "STOCK_COUNT", id, u.id());
                ev.add(Map.of("itemName", info.get("name"), "division", info.get("division_code"), "qty", l.qty(), "amount", amt));
            }
            outbox.publish("inventory", "STOCK_COUNT", id, "StockConsumed", Map.of("countId", id, "storeId", store, "countedAt", java.time.OffsetDateTime.now().toString(), "lines", ev));
            trail.log("STOCK_COUNT", id, "IN_PROGRESS", "SUBMITTED", u.id(), "판매 소진 " + ev.size() + "건 매출원가 반영");
        } else if (!shortLines.isEmpty()) {
            adjId = adj.create(new AdjustmentService.SaveReq(store, "SHORTAGE", "재고 실사 차이 (자동 생성)", shortLines, false), u);
            db.sql("update inventory.stock_adjustment set source_type = 'STOCK_COUNT', source_id = ? where id = ?").params(id, adjId).update();
        }
        db.sql("update inventory.stock_count set status = 'SUBMITTED', submitted_at = now(), adjustment_id = ? where id = ?").params(adjId, id).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shortageAdjustmentId", adjId); out.put("shortItems", shortLines.size()); out.put("surplusItems", surplus); out.put("mode", usage ? "USAGE" : "SHORTAGE");
        return out;
    }
}
