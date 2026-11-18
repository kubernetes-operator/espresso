package kr.espresso.core.approval;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/core/approvals")
public class ApprovalController {
    private final ApprovalService svc;
    private final JdbcClient db;

    public ApprovalController(ApprovalService svc, JdbcClient db) { this.svc = svc; this.db = db; }

    /** box = pending(내 차례) / mine(내가 올린) / done(내가 처리한) / all(총괄: 진행 중 전체) */
    @GetMapping
    public List<Map<String, Object>> inbox(@RequestParam(defaultValue = "pending") String box) {
        return svc.inbox(box, CurrentUser.get());
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        Integer n = db.sql("select count(*) from approval.step where status = 'PENDING' and ? = any(assignee_ids)")
                .param(CurrentUser.get().id()).query(Integer.class).single();
        return Map.of("pending", n);
    }

    @GetMapping("/doc/{docType}/{docId}")
    public Map<String, Object> forDoc(@PathVariable String docType, @PathVariable UUID docId) {
        CurrentUser.get();
        return svc.forDoc(docType, docId);
    }

    public record ActReq(String comment) {}

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) ActReq req) {
        svc.act(id, true, req == null ? null : req.comment(), CurrentUser.get());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody ActReq req) {
        svc.act(id, false, req.comment(), CurrentUser.get());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable UUID id) {
        svc.withdraw(id, CurrentUser.get());
        return Map.of("ok", true);
    }

    // ---------------- 결재선 관리 (총괄) ----------------
    @GetMapping("/lines")
    public List<Map<String, Object>> lines() {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var t : db.sql("""
                select t.*, s.name as store_name from approval.line_template t left join store.store s on s.id = t.store_id
                where t.is_active order by t.doc_type, t.is_default desc, t.store_id nulls first, t.min_amount nulls first""").query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("steps", db.sql("select seq, approver_type, role_code, user_id, skip_if_requester from approval.line_template_step where template_id = ? order by seq")
                    .param(t.get("id")).query().listOfRows());
            out.add(m);
        }
        return out;
    }

    public record StepReq(String approverType, String roleCode, UUID userId, Boolean skipIfRequester) {}
    public record LineReq(String docType, String name, UUID storeId, Long minAmount, Long maxAmount, Integer priority, List<StepReq> steps) {}

    @PostMapping("/lines")
    @Transactional
    public Map<String, Object> createLine(@RequestBody LineReq req) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (req.steps() == null || req.steps().isEmpty()) throw ApiException.bad("결재 단계를 1개 이상 넣으세요.");
        UUID id = UUID.randomUUID();
        db.sql("insert into approval.line_template(id, doc_type, name, store_id, min_amount, max_amount, priority, created_by) values (?,?,?,?,?,?,?,?)")
                .params(id, req.docType(), req.name(), req.storeId(), req.minAmount(), req.maxAmount(),
                        req.priority() == null ? 10 : req.priority(), u.id()).update();
        insertSteps(id, req.steps());
        return Map.of("id", id);
    }

    @PutMapping("/lines/{id}")
    @Transactional
    public Map<String, Object> updateLine(@PathVariable UUID id, @RequestBody LineReq req) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        if (req.steps() == null || req.steps().isEmpty()) throw ApiException.bad("결재 단계를 1개 이상 넣으세요.");
        db.sql("update approval.line_template set name = coalesce(cast(? as varchar), name), min_amount = ?, max_amount = ? where id = ?")
                .params(req.name(), req.minAmount(), req.maxAmount(), id).update();
        db.sql("delete from approval.line_template_step where template_id = ?").param(id).update();
        insertSteps(id, req.steps());
        return Map.of("id", id);
    }

    @DeleteMapping("/lines/{id}")
    public Map<String, Object> deleteLine(@PathVariable UUID id) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        int n = db.sql("update approval.line_template set is_active = false where id = ? and not is_default").param(id).update();
        if (n == 0) throw ApiException.bad("기본(전체) 결재선은 삭제할 수 없습니다. 단계만 수정하세요.");
        return Map.of("ok", true);
    }

    private void insertSteps(UUID tpl, List<StepReq> steps) {
        int seq = 1;
        for (StepReq s : steps) {
            db.sql("insert into approval.line_template_step(template_id, seq, approver_type, role_code, user_id, skip_if_requester) values (?,?,?,?,?,?)")
                    .params(tpl, seq++, s.approverType(), "ROLE".equals(s.approverType()) ? s.roleCode() : null,
                            "USER".equals(s.approverType()) ? s.userId() : null, s.skipIfRequester() == null || s.skipIfRequester()).update();
        }
    }
}
