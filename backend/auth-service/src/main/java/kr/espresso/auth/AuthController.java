package kr.espresso.auth;

import com.nimbusds.jose.jwk.JWKSet;
import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.RoleScope;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
public class AuthController {
    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final JWKSet jwks;
    private final long ttlHours;
    private final boolean showDemo;

    public AuthController(JdbcClient db, PasswordEncoder encoder, JwtEncoder jwt, JWKSet jwks,
                          @Value("${app.token-ttl-hours}") long ttlHours,
                          @Value("${app.demo.show-accounts}") boolean showDemo) {
        this.db = db; this.encoder = encoder; this.jwt = jwt; this.jwks = jwks;
        this.ttlHours = ttlHours; this.showDemo = showDemo;
    }

    public record LoginReq(@NotBlank String loginId, @NotBlank String password) {}

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() { return jwks.toJSONObject(); }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq req, HttpServletRequest http) {
        var row = db.sql("select id, name, password_hash, status, vat_display_override from auth.app_user where login_id = ?")
                .param(req.loginId()).query().listOfRows();
        boolean ok = !row.isEmpty() && encoder.matches(req.password(), (String) row.get(0).get("password_hash"))
                && "ACTIVE".equals(row.get(0).get("status"));
        UUID uid = row.isEmpty() ? null : (UUID) row.get(0).get("id");
        db.sql("insert into auth.login_history(user_id, login_id, success, user_agent) values (?,?,?,?)")
                .params(uid, req.loginId(), ok, http.getHeader("User-Agent")).update();
        if (!ok) throw new ApiException(401, "LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다.");

        db.sql("update auth.app_user set last_login_at = now() where id = ?").param(uid).update();
        Map<String, Object> u = row.get(0);
        List<Map<String, Object>> asg = activeAssignments(uid);
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("espresso-auth").subject(uid.toString()).issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(ttlHours)))
                .claim("login", req.loginId()).claim("name", u.get("name")).claim("asg", asg);
        if (u.get("vat_display_override") != null) claims.claim("vat", u.get("vat_display_override"));
        String token = jwt.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
        return Map.of("accessToken", token, "expiresInSec", ttlHours * 3600,
                "user", Map.of("id", uid, "loginId", req.loginId(), "name", u.get("name"),
                        "assignments", asg, "vatOverride", Objects.toString(u.get("vat_display_override"), "")));
    }

    /** 활성 등급만 토큰에 포함 (비활성 등급: 슈퍼바이저 등) */
    List<Map<String, Object>> activeAssignments(UUID uid) {
        return db.sql("""
                select a.role_code, a.scope_type, a.scope_id from auth.user_role_assignment a
                join auth.role r on r.code = a.role_code
                where a.user_id = ? and r.is_active and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                order by r.rank""").param(uid).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("r", rs.getString(1)); m.put("t", rs.getString(2));
            m.put("s", rs.getString(3));
            return m;
        }).list();
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me() {
        CurrentUser u = CurrentUser.get();
        return Map.of("id", u.id(), "loginId", u.loginId(), "name", u.name(),
                "assignments", u.assignments(), "vatOverride", Objects.toString(u.vatOverride(), ""));
    }

    /** 데모: 로그인 화면 계정 바로가기 (app.demo.show-accounts=false 이면 빈 목록) */
    @GetMapping("/api/auth/demo-accounts")
    public List<Map<String, Object>> demoAccounts() {
        if (!showDemo) return List.of();
        return db.sql("""
                select u.login_id, u.name, string_agg(distinct r.name, ', ') as roles, min(r.rank) as rk
                from auth.app_user u join auth.user_role_assignment a on a.user_id = u.id
                join auth.role r on r.code = a.role_code
                where u.status = 'ACTIVE' group by u.login_id, u.name order by rk, u.login_id""")
                .query().listOfRows();
    }

    static RoleScope toScope(Map<String, Object> m) {
        Object s = m.get("s");
        return new RoleScope((String) m.get("r"), (String) m.get("t"), s == null ? null : UUID.fromString(s.toString()));
    }
}
