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

    public List<UUID> approvers(String role, UUID storeId, UUID regionId) {
        List<UUID> r = http.get().uri(u -> u.path("/internal/users/approvers").queryParam("role", role)
                        .queryParamIfPresent("storeId", Optional.ofNullable(storeId))
                        .queryParamIfPresent("regionId", Optional.ofNullable(regionId)).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
        return r == null ? List.of() : r;
    }
}
