package kr.espresso.settlement.support;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/** core-service 호출: 사용자 매장 범위(사용자 토큰 전달), 내부 API(재고 금액·매장) */
@Component
public class CoreClient {
    private final RestClient http;
    private final HttpServletRequest req;

    public CoreClient(@Value("${app.core-url}") String url, HttpServletRequest req) {
        this.http = RestClient.create(url); this.req = req;
    }

    private String bearer() { return req.getHeader("Authorization"); }

    /** 로그인 사용자가 볼 수 있는 매장 [{id,name,...}] */
    public List<Map<String, Object>> myStores() {
        List<Map<String, Object>> r = http.get().uri("/api/core/stores").header("Authorization", bearer()).retrieve().body(new ParameterizedTypeReference<>() {});
        return r == null ? List.of() : r;
    }

    public Set<UUID> myStoreIds() {
        Set<UUID> s = new LinkedHashSet<>();
        myStores().forEach(m -> s.add(UUID.fromString(m.get("id").toString())));
        return s;
    }

    public void check(UUID storeId) {
        if (storeId == null || !myStoreIds().contains(storeId)) throw ApiException.forbidden("이 매장에 접근할 권한이 없습니다.");
    }

    /** 매장 범위 + 해당 매장에서 지정 등급 보유 확인 (총괄은 통과) */
    public void requireOn(CurrentUser u, UUID storeId, String... roles) {
        check(storeId);
        if (u.isHqAdmin()) return;
        Set<String> want = Set.of(roles);
        boolean ok = u.assignments().stream().anyMatch(a -> want.contains(a.role()) && (a.isAll() || storeId.equals(a.scopeId())));
        if (!ok) throw ApiException.forbidden("이 매장에서 이 작업을 할 권한이 없습니다.");
    }

    public long stockValue(UUID storeId) {
        Map<String, Object> m = http.get().uri("/internal/inventory/value?storeId={s}", storeId).retrieve().body(new ParameterizedTypeReference<>() {});
        return m == null ? 0 : ((Number) m.get("value")).longValue();
    }

    public List<Map<String, Object>> allStores() {
        List<Map<String, Object>> r = http.get().uri("/internal/stores").retrieve().body(new ParameterizedTypeReference<>() {});
        return r == null ? List.of() : r;
    }

    public List<Map<String, Object>> monthlySales(UUID store, String from, String to) {
        List<Map<String, Object>> r = http.get().uri("/internal/sales/monthly?storeId={s}&from={f}&to={t}", store, from, to).retrieve().body(new ParameterizedTypeReference<>() {});
        return r == null ? List.of() : r;
    }

    public List<Map<String, Object>> leases(UUID store) {
        List<Map<String, Object>> r = http.get().uri("/internal/stores/{id}/leases", store).retrieve().body(new ParameterizedTypeReference<>() {});
        return r == null ? List.of() : r;
    }

    /** 비용 결재 시작 (사용자 토큰으로 core 결재 엔진 호출) */
    public void startExpenseApproval(UUID expenseId) {
        http.post().uri("/api/core/approvals/expense/start").header("Authorization", bearer())
                .body(Map.of("docId", expenseId)).retrieve().toBodilessEntity();
    }
}
