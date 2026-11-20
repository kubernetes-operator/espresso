package kr.espresso.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import kr.espresso.common.ActiveUserValidator;
import kr.espresso.common.SecuritySupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 서명 키: 기동 시 메모리에서 RSA 키를 생성하고 공개키만 JWKS 로 노출한다.
 * → 설정 파일에 비밀값을 두지 않는다. (재기동 시 기존 토큰은 무효화 → 다시 로그인)
 * 운영 전환 시 KMS/K8s Secret 에 보관된 키를 주입하는 방식으로 교체.
 */
@Configuration
public class KeyConfig {

    @Bean
    RSAKey rsaKey() throws Exception {
        return new RSAKeyGenerator(2048).keyID("espresso-" + System.currentTimeMillis()).generate();
    }

    @Bean
    JWKSet jwkSet(RSAKey key) { return new JWKSet(key.toPublicJWK()); }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, JdbcClient db) throws Exception {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        // 정지된 계정은 토큰이 남아 있어도 거부
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new ActiveUserValidator(id ->
                db.sql("select status from auth.app_user where id = ?").param(id).query(String.class).optional().orElse(null))));
        return d;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return SecuritySupport.chain(http, decoder,
                "/api/auth/login", "/api/auth/demo-accounts", "/.well-known/jwks.json");
    }
}
