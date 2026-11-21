package kr.espresso.core.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/** auth-service 내부 API 호출 (승인자 해석) */
@Component
public class AuthClient {
    private final RestClient http;

    public AuthClient(@Value("${app.auth-url}") String base) { this.http = RestClient.create(base); }

    public void grant(UUID user, String role, String scopeType, UUID scopeId) {
        Map<String, Object> b = new HashMap<>();
        b.put("role", role); b.put("scopeType", scopeType); b.put("scopeId", scopeId);
        http.post().uri("/internal/users/{id}/assignments", user).body(b).retrieve().toBodilessEntity();
    }

    /** 계정 생성: 사용자 토큰을 그대로 전달 → auth 가 권한 확인 (오류 메시지는 그대로 전달) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createUser(String bearer, Map<String, Object> body) {
        try {
            return http.post().uri("/api/auth/users").header("Authorization", bearer).body(body).retrieve().body(Map.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String msg = "계정을 만들지 못했습니다.";
            try { msg = (String) new com.fasterxml.jackson.databind.ObjectMapper().readValue(e.getResponseBodyAsString(), Map.class).getOrDefault("message", msg); } catch (Exception ignore) { }
            throw new kr.espresso.common.ApiException(e.getStatusCode().value(), "AUTH_ERROR", msg);
        }
    }

    public void rename(UUID user, String name) {
        http.put().uri("/internal/users/{id}/name", user).body(Map.of("name", name)).retrieve().toBodilessEntity();
    }

    public List<UUID> approvers(String role, UUID storeId, UUID regionId) {
        List<UUID> r = http.get().uri(u -> u.path("/internal/users/approvers").queryParam("role", role)
                        .queryParamIfPresent("storeId", Optional.ofNullable(storeId))
                        .queryParamIfPresent("regionId", Optional.ofNullable(regionId)).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
        return r == null ? List.of() : r;
    }
}
