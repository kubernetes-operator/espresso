package kr.espresso.core.logistics;

import kr.espresso.common.CurrentUser;
import kr.espresso.core.approval.ApprovalService;
import kr.espresso.core.support.DocTrail;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/core/logistics")
public class LogisticsController {
    private final LogisticsService svc;
    private final JdbcClient db;
    private final StoreScope scope;
    private final ApprovalService approvals;
    private final DocTrail trail;

    public LogisticsController(LogisticsService svc, JdbcClient db, StoreScope scope, ApprovalService approvals, DocTrail trail) {
        this.svc = svc; this.db = db; this.scope = scope; this.approvals = approvals; this.trail = trail;
    }

    /** 목록: storeId 미지정 시 볼 수 있는 모든 매장 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID storeId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> stores = scope.allowed(u);
        if (storeId != null) { scope.check(u, storeId); stores = Set.of(storeId); }
        if (stores.isEmpty()) return List.of();
        return db.sql("""
                select r.id, r.request_no, r.store_id, s.name as store_name, r.title, r.status, r.taxable_supply, r.exempt_supply,
                       r.vat_amount, r.shipping_fee, r.total_amount, r.requested_at, r.approved_at, r.shipped_at, r.completed_at,
                       r.created_at, r.created_by, r.desired_date, r.due_date, r.kind, r.is_initial_order,
                       (select count(*) from logistics.request_line l where l.request_id = r.id) as line_count,
                       (select count(*) from logistics.request_line l where l.request_id = r.id and l.source_type = 'EXTERNAL') as external_count
                from logistics.request r join store.store s on s.id = r.store_id
                where r.store_id in (:stores) and (cast(:st as varchar) is null or r.status = cast(:st as varchar))
                  and (cast(:from as date) is null or r.created_at >= cast(:from as date))
                  and (cast(:to as date) is null or r.created_at < cast(:to as date) + 1)
                order by r.created_at desc limit 300""")
                .param("stores", stores).param("st", status).param("from", from).param("to", to).query().listOfRows();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        Map<String, Object> r = new LinkedHashMap<>(svc.load(id));
        scope.check(u, (UUID) r.get("store_id"));
        r.put("store_name", db.sql("select name from store.store where id = ?").param(r.get("store_id")).query(String.class).single());
        r.put("lines", db.sql("""
                select l.*, c.name as category_name, c.division_code from logistics.request_line l
                join catalog.category c on c.id = l.category_id where l.request_id = ? order by l.line_no""").param(id).query().listOfRows());
        r.put("shipments", db.sql("select * from logistics.shipment where request_id = ? order by shipped_at").param(id).query().listOfRows());
        r.put("related", db.sql("select id, request_no, kind, status from logistics.request where parent_request_id = ? or id = ? order by created_at")
                .params(id, r.get("parent_request_id")).query().listOfRows());
        r.put("approval", approvals.forDoc(LogisticsService.DOC, id));
        r.put("history", trail.history(LogisticsService.DOC, id));
        return r;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody LogisticsService.SaveReq req) {
        return Map.of("id", svc.create(req, CurrentUser.get()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody LogisticsService.SaveReq req) {
        svc.update(id, req, CurrentUser.get());
        return Map.of("id", id);
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable UUID id) { svc.submit(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/{id}/purchase")
    public Map<String, Object> purchase(@PathVariable UUID id, @RequestBody Map<String, List<UUID>> body) {
        svc.markPurchased(id, body.getOrDefault("lineIds", List.of()), CurrentUser.get());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/prepare")
    public Map<String, Object> prepare(@PathVariable UUID id) { svc.prepare(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/{id}/ship")
    public Map<String, Object> ship(@PathVariable UUID id, @RequestBody LogisticsService.ShipReq req) {
        svc.ship(id, req, CurrentUser.get()); return Map.of("ok", true);
    }

    @PostMapping("/{id}/deliver")
    public Map<String, Object> deliver(@PathVariable UUID id) { svc.deliver(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/{id}/receive")
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody LogisticsService.ReceiveReq req) {
        return svc.receive(id, req, CurrentUser.get());
    }

    @PutMapping("/{id}/due-date")
    public Map<String, Object> due(@PathVariable UUID id, @RequestBody LogisticsService.DueReq req) { svc.setDue(id, req, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) { svc.cancel(id, CurrentUser.get()); return Map.of("ok", true); }

    @PutMapping("/{id}/lines/{lineId}")
    public Map<String, Object> adjust(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody LogisticsService.LinePriceReq req) {
        svc.adjustLine(id, lineId, req, CurrentUser.get()); return Map.of("ok", true);
    }
}
