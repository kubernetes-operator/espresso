package kr.espresso.common;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 정지된 계정의 토큰 거부: 토큰이 아직 유효해도 계정 상태가 ACTIVE 가 아니면 401.
 * 상태 조회 결과는 30초 캐시 (정지 후 최대 30초 안에 모든 서비스에서 차단).
 * 조회 실패(auth 일시 장애) 시에는 마지막 값 또는 허용 — 로그인 자체는 auth 가 막는다.
 */
public class ActiveUserValidator implements OAuth2TokenValidator<Jwt> {
    private static final long TTL_MS = 30_000;
    private final Function<UUID, String> statusLookup;
    private final Map<UUID, Object[]> cache = new ConcurrentHashMap<>();

    public ActiveUserValidator(Function<UUID, String> statusLookup) { this.statusLookup = statusLookup; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID id;
        try { id = UUID.fromString(jwt.getSubject()); } catch (Exception e) { return OAuth2TokenValidatorResult.success(); }
        Object[] c = cache.get(id);
        String status;
        if (c != null && System.currentTimeMillis() - (Long) c[1] < TTL_MS) status = (String) c[0];
        else {
            try { status = statusLookup.apply(id); cache.put(id, new Object[]{status, System.currentTimeMillis()}); }
            catch (Exception e) { status = c == null ? "ACTIVE" : (String) c[0]; }
        }
        if (status == null || "ACTIVE".equals(status)) return OAuth2TokenValidatorResult.success();
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "정지된 계정입니다.", null));
    }
}
