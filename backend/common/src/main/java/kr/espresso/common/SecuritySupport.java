package kr.espresso.common;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 각 서비스가 같은 규칙으로 SecurityFilterChain 을 만들도록 하는 헬퍼. */
public final class SecuritySupport {
    private SecuritySupport() {}

    public static SecurityFilterChain chain(HttpSecurity http, JwtDecoder decoder, String... publicPaths) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                // /internal/** 은 클러스터 내부 전용 (nginx/Ingress 에서 외부 노출하지 않음)
                // 운영 전환 시 mTLS 또는 client-credentials 토큰으로 보호
                a.requestMatchers("/internal/**").permitAll();
                if (publicPaths.length > 0) a.requestMatchers(publicPaths).permitAll();
                a.anyRequest().authenticated();
            })
            .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
        return http.build();
    }
}
