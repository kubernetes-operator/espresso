package kr.espresso.core.approval;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;

/** settlement-service 의 비용(EXPENSE) 문서를 core 결재 엔진으로 결재 */
@Component
@RestController
public class ExpenseApprovalHandler implements ApprovalHandler {
    private final RestClient http;
    private final ApprovalService approvals;

    public ExpenseApprovalHandler(@Value("${app.settlement-url}") String url, @org.springframework.context.annotation.Lazy ApprovalService approvals) {
        this.http = RestClient.create(url); this.approvals = approvals;
    }

    @Override public String docType() { return "EXPENSE"; }
    @Override public String docLabel() { return "비용"; }

    @Override
    public Map<String, Object> summary(UUID docId) {
        Map<String, Object> m = http.get().uri("/internal/expenses/{id}/summary", docId).retrieve().body(new ParameterizedTypeReference<>() {});
        return m == null ? Map.of() : m;
    }

    @Override public void onApproved(UUID docId, UUID actor) { callback(docId, "APPROVED", actor, null); }
    @Override public void onRejected(UUID docId, UUID actor, String comment) { callback(docId, "REJECTED", actor, comment); }
    @Override public void onWithdrawn(UUID docId, UUID actor) { callback(docId, "DRAFT", actor, "회수"); }

    private void callback(UUID id, String status, UUID actor, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status); body.put("actor", actor); body.put("comment", comment);
        http.post().uri("/internal/expenses/{id}/status", id).body(body).retrieve().toBodilessEntity();
    }

    public record StartReq(UUID docId) {}

    /** settlement 가 사용자 토큰을 그대로 넘겨 호출: 비용 결재 시작 */
    @PostMapping("/api/core/approvals/expense/start")
    public Map<String, Object> start(@RequestBody StartReq req) {
        CurrentUser u = CurrentUser.get();
        var s = summary(req.docId());
        if (!"REQUESTED".equals(s.get("status")) || !u.id().toString().equals(String.valueOf(s.get("requestedBy"))))
            throw ApiException.bad("상신 상태의 본인 비용 문서만 결재를 시작할 수 있습니다.");
        UUID store = UUID.fromString(s.get("storeId").toString());
        long amount = ((Number) s.get("amount")).longValue();
        return Map.of("approvalId", approvals.start("EXPENSE", req.docId(), store, amount, u));
    }
}
