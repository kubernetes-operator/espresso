package kr.espresso.core;

import kr.espresso.common.ActiveUserValidator;
import kr.espresso.common.SecuritySupport;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.web.client.RestClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwks-uri}") String jwks, @Value("${app.auth-url}") String authUrl) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwks).build();   // auth-service 공개키로 검증
        RestClient auth = RestClient.create(authUrl);
        // 정지된 계정은 토큰이 남아 있어도 거부 (상태 30초 캐시)
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new ActiveUserValidator(id -> {
            Map<?, ?> m = auth.get().uri("/internal/users/{id}/status", id).retrieve().body(Map.class);
            return m == null ? null : (String) m.get("status");
        })));
        return d;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return SecuritySupport.chain(http, decoder);
    }
}
