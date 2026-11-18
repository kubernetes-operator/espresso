package kr.espresso.core.approval;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.core.support.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.*;

/**
 * 범용 결재 엔진 (요구 10, 19)
 * - 결재선: 매장 지정 > 금액 구간 > 문서 기본(전체 결재) 순으로 선택
 * - 요청자 등급 이하의 단계는 자동 통과 (예: 점주가 요청하면 매장관리자·점주 단계 생략)
 * - 승인자가 없는 단계는 '승인자 없음'으로 통과 처리하고 기록
 */
@Service
public class ApprovalService {
    static final Map<String, Integer> LEVEL = Map.of(
            Roles.STAFF, 0, Roles.STORE_MGR, 1, Roles.OWNER, 2,
            Roles.LOGISTICS_MGR, 3, Roles.HR_MGR, 3, Roles.SUPERVISOR, 3, Roles.HQ_ADMIN, 4);

    static final Set<String> STORE_LINE = Set.of(Roles.STAFF, Roles.STORE_MGR, Roles.OWNER);
    private final JdbcClient db;
    private final AuthClient auth;
    private final StoreScope scope;
    private final Notifier notifier;
    private final Map<String, ApprovalHandler> handlers = new HashMap<>();

    public ApprovalService(JdbcClient db, AuthClient auth, StoreScope scope, Notifier notifier, @Lazy List<ApprovalHandler> hs) {
        this.db = db; this.auth = auth; this.scope = scope; this.notifier = notifier;
        this.lazyHandlers = hs;
    }
    private final List<ApprovalHandler> lazyHandlers;

    ApprovalHandler handler(String docType) {
        if (handlers.isEmpty()) lazyHandlers.forEach(h -> handlers.put(h.docType(), h));
        ApprovalHandler h = handlers.get(docType);
        if (h == null) throw new IllegalStateException("no handler " + docType);
        return h;
    }

    int requesterLevel(CurrentUser u, UUID storeId) {
        int lv = -1;
        for (var e : LEVEL.entrySet())
            if (scope.hasRoleOn(u, storeId, e.getKey())) lv = Math.max(lv, e.getValue());
        return lv;
    }

    @Transactional
    public UUID start(String docType, UUID docId, UUID storeId, long amount, CurrentUser requester) {
        var tpl = db.sql("""
                select id from approval.line_template
                where doc_type = :dt and is_active
                  and (store_id is null or store_id = :store)
                  and (min_amount is null or :amt >= min_amount)
                  and (max_amount is null or :amt < max_amount)
                order by (store_id is not null) desc, is_default asc, priority desc limit 1""")
                .param("dt", docType).param("store", storeId).param("amt", amount).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.bad("결재선이 설정되어 있지 않습니다: " + docType));
        var steps = db.sql("select seq, approver_type, role_code, user_id, skip_if_requester from approval.line_template_step where template_id = ? order by seq")
                .param(tpl).query().listOfRows();

        UUID reqId = UUID.randomUUID();
        db.sql("insert into approval.request(id, doc_type, doc_id, store_id, amount, template_id, requested_by) values (?,?,?,?,?,?,?)")
                .params(reqId, docType, docId, storeId, amount, tpl, requester.id()).update();

        int myLevel = requesterLevel(requester, storeId);
        Set<String> myRoles = new HashSet<>();
        for (String r : LEVEL.keySet()) if (scope.hasRoleOn(requester, storeId, r)) myRoles.add(r);
        boolean admin = myRoles.contains(Roles.HQ_ADMIN);
        UUID region = scope.regionOf(storeId);
        for (var s : steps) {
            String type = (String) s.get("approver_type");
            String role = (String) s.get("role_code");
            List<UUID> assignees = "USER".equals(type) ? List.of((UUID) s.get("user_id")) : auth.approvers(role, storeId, region);
            String status = "WAITING", comment = null;
            boolean skipSelf = Boolean.TRUE.equals(s.get("skip_if_requester"));
            // 자동 통과: 요청자 본인 등급 / 같은 매장 라인의 하위 등급 / 총괄이 요청한 경우 전부
            boolean storeLineLower = STORE_LINE.contains(role) && myRoles.stream().anyMatch(STORE_LINE::contains)
                    && LEVEL.getOrDefault(role, 9) <= myLevel;
            if ("ROLE".equals(type) && skipSelf && (admin || myRoles.contains(role) || storeLineLower)) { status = "SKIPPED"; comment = "요청자 등급 이하 단계 자동 통과"; }
            else if ("USER".equals(type) && skipSelf && assignees.contains(requester.id())) { status = "SKIPPED"; comment = "요청자 본인 단계 자동 통과"; }
            else if (assignees.isEmpty()) { status = "SKIPPED"; comment = "지정된 승인자 없음"; }
            db.sql("insert into approval.step(request_id, seq, approver_type, role_code, assignee_ids, status, comment, acted_at) values (?,?,?,?,cast(? as uuid[]),?,?, case when ? = 'SKIPPED' then now() end)")
                    .params(reqId, ((Number) s.get("seq")).intValue(), type, role, toArray(assignees), status, comment, status).update();
        }
        advance(reqId, requester.id());
        return reqId;
    }

    private static String toArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    /** 다음 대기 단계를 활성화. 없으면 최종 승인 */
    private void advance(UUID reqId, UUID actor) {
        var next = db.sql("select id, assignee_ids, role_code from approval.step where request_id = ? and status = 'WAITING' order by seq limit 1")
                .param(reqId).query().listOfRows();
        var req = db.sql("select doc_type, doc_id, store_id from approval.request where id = ?").param(reqId).query().singleRow();
        String docType = (String) req.get("doc_type");
        UUID docId = (UUID) req.get("doc_id");
        if (next.isEmpty()) {
            db.sql("update approval.request set status = 'APPROVED', current_seq = null, completed_at = now() where id = ?").param(reqId).update();
            handler(docType).onApproved(docId, actor);
            return;
        }
        UUID stepId = (UUID) next.get(0).get("id");
        db.sql("update approval.step set status = 'PENDING', activated_at = now() where id = ?").param(stepId).update();
        db.sql("update approval.request set current_seq = (select seq from approval.step where id = ?) where id = ?").params(stepId, reqId).update();
        List<UUID> assignees = uuidList(next.get(0).get("assignee_ids"));
        var sum = handler(docType).summary(docId);
        notifier.send(assignees, "APPROVAL_PENDING", handler(docType).docLabel() + " 결재 요청",
                sum.get("no") + " " + Objects.toString(sum.get("title"), ""), "/approvals", (UUID) req.get("store_id"));
    }

    static List<UUID> uuidList(Object arr) {
        try {
            if (arr instanceof Array a) return Arrays.stream((Object[]) a.getArray()).map(o -> (UUID) o).toList();
            if (arr instanceof Object[] o) return Arrays.stream(o).map(x -> (UUID) x).toList();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return List.of();
    }

    @Transactional
    public void act(UUID reqId, boolean approve, String comment, CurrentUser u) {
        var reqRows = db.sql("select doc_type, doc_id, status, requested_by, store_id from approval.request where id = ? for update").param(reqId).query().listOfRows();
        if (reqRows.isEmpty()) throw ApiException.notFound("결재");
        var req = reqRows.get(0);
        if (!"IN_PROGRESS".equals(req.get("status"))) throw ApiException.conflict("이미 끝난 결재입니다.");
        var step = db.sql("select id, role_code, assignee_ids from approval.step where request_id = ? and status = 'PENDING'").param(reqId).query().singleRow();
        List<UUID> assignees = uuidList(step.get("assignee_ids"));
        boolean mine = assignees.contains(u.id());
        boolean override = !mine && u.isHqAdmin();
        if (!mine && !override) throw ApiException.forbidden("지금 단계의 승인자가 아닙니다.");
        if (!approve && (comment == null || comment.isBlank())) throw ApiException.bad("반려 사유를 입력하세요.");
        String docType = (String) req.get("doc_type");
        UUID docId = (UUID) req.get("doc_id");
        String c = override ? "[총괄 직권] " + Objects.toString(comment, "") : comment;
        db.sql("update approval.step set status = ?, acted_by = ?, acted_at = now(), comment = ?, is_override = ? where id = ?")
                .params(approve ? "APPROVED" : "REJECTED", u.id(), c, override, step.get("id")).update();
        if (approve) {
            handler(docType).onStepApproved(docId, (String) step.get("role_code"), u.id());
            advance(reqId, u.id());
            Map<String, Object> st = db.sql("select status from approval.request where id = ?").param(reqId).query().singleRow();
            if ("APPROVED".equals(st.get("status")))
                notifier.send(List.of((UUID) req.get("requested_by")), "APPROVED", handler(docType).docLabel() + " 최종 승인",
                        Objects.toString(handler(docType).summary(docId).get("no"), ""), "/approvals", (UUID) req.get("store_id"));
        } else {
            db.sql("update approval.step set status = 'SKIPPED', comment = '앞 단계 반려', acted_at = now() where request_id = ? and status = 'WAITING'").param(reqId).update();
            db.sql("update approval.request set status = 'REJECTED', current_seq = null, completed_at = now() where id = ?").param(reqId).update();
            handler(docType).onRejected(docId, u.id(), comment);
            notifier.send(List.of((UUID) req.get("requested_by")), "REJECTED", handler(docType).docLabel() + " 반려",
                    handler(docType).summary(docId).get("no") + " · " + comment, "/approvals", (UUID) req.get("store_id"));
        }
    }

    @Transactional
    public void withdraw(UUID reqId, CurrentUser u) {
        var req = db.sql("select doc_type, doc_id, status, requested_by from approval.request where id = ?").param(reqId).query().singleRow();
        if (!u.id().equals(req.get("requested_by"))) throw ApiException.forbidden("요청자만 회수할 수 있습니다.");
        if (!"IN_PROGRESS".equals(req.get("status"))) throw ApiException.conflict("진행 중인 결재만 회수할 수 있습니다.");
        Integer acted = db.sql("select count(*) from approval.step where request_id = ? and status = 'APPROVED'").param(reqId).query(Integer.class).single();
        if (acted > 0) throw ApiException.conflict("이미 승인된 단계가 있어 회수할 수 없습니다.");
        db.sql("update approval.step set status = 'SKIPPED', comment = '요청자 회수', acted_at = now() where request_id = ? and status in ('WAITING','PENDING')").param(reqId).update();
        db.sql("update approval.request set status = 'WITHDRAWN', completed_at = now() where id = ?").param(reqId).update();
        handler((String) req.get("doc_type")).onWithdrawn((UUID) req.get("doc_id"), u.id());
    }

    public Map<String, Object> forDoc(String docType, UUID docId) {
        var reqs = db.sql("select * from approval.request where doc_type = ? and doc_id = ? order by requested_at desc limit 1")
                .params(docType, docId).query().listOfRows();
        if (reqs.isEmpty()) return Map.of();
        Map<String, Object> r = new LinkedHashMap<>(reqs.get(0));
        r.put("steps", stepsOf((UUID) r.get("id")));
        return r;
    }

    public List<Map<String, Object>> stepsOf(UUID reqId) {
        return db.sql("select id, seq, approver_type, role_code, assignee_ids, status, acted_by, is_override, activated_at, acted_at, comment from approval.step where request_id = ? order by seq")
                .param(reqId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id")); m.put("seq", rs.getInt("seq"));
                    m.put("approverType", rs.getString("approver_type")); m.put("role", rs.getString("role_code"));
                    m.put("assignees", uuidList(rs.getArray("assignee_ids")));
                    m.put("status", rs.getString("status")); m.put("actedBy", rs.getObject("acted_by"));
                    m.put("override", rs.getBoolean("is_override"));
                    m.put("activatedAt", rs.getObject("activated_at")); m.put("actedAt", rs.getObject("acted_at"));
                    m.put("comment", rs.getString("comment"));
                    return m;
                }).list();
    }

    public List<Map<String, Object>> inbox(String box, CurrentUser u) {
        String sql = switch (box) {
            case "pending" -> """
                    select r.* from approval.request r join approval.step s on s.request_id = r.id
                    where s.status = 'PENDING' and :me = any(s.assignee_ids) order by r.requested_at""";
            case "mine" -> "select r.* from approval.request r where r.requested_by = :me order by r.requested_at desc limit 100";
            case "done" -> """
                    select distinct r.* from approval.request r join approval.step s on s.request_id = r.id
                    where s.acted_by = :me order by r.requested_at desc limit 100""";
            case "all" -> "select r.* from approval.request r where r.status = 'IN_PROGRESS' order by r.requested_at limit 200";
            default -> throw ApiException.bad("box: pending/mine/done/all");
        };
        if ("all".equals(box) && !u.isHqAdmin()) throw ApiException.forbidden("총괄 관리자만 볼 수 있습니다.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : db.sql(sql).param("me", u.id()).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            ApprovalHandler h = handler((String) r.get("doc_type"));
            m.put("docLabel", h.docLabel());
            m.put("doc", h.summary((UUID) r.get("doc_id")));
            m.put("steps", stepsOf((UUID) r.get("id")));
            out.add(m);
        }
        return out;
    }
}
