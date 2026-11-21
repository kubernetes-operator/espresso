package kr.espresso.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/** JWT 클레임에서 복원한 로그인 사용자. */
public record CurrentUser(UUID id, String loginId, String name, List<RoleScope> assignments, String vatOverride) {

    public static CurrentUser get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken t) return from(t.getToken());
        throw new ApiException(401, "UNAUTHENTICATED", "로그인이 필요합니다.");
    }

    @SuppressWarnings("unchecked")
    public static CurrentUser from(Jwt jwt) {
        List<RoleScope> list = new ArrayList<>();
        Object raw = jwt.getClaim("asg");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                Object sid = m.get("s");
                list.add(new RoleScope((String) m.get("r"), (String) m.get("t"),
                        sid == null ? null : UUID.fromString(sid.toString())));
            }
        }
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("login"),
                jwt.getClaimAsString("name"), expand(list), jwt.getClaimAsString("vat"));
    }

    /**
     * 총괄 관리자·슈퍼바이저는 같은 범위에서 물류 관리자·인사 관리자 권한을 함께 가진다 (2차 점검 반영).
     * 실제 배정은 그대로 두고 권한 판단용 배정만 덧붙인다.
     */
    static List<RoleScope> expand(List<RoleScope> list) {
        List<RoleScope> out = new ArrayList<>(list);
        for (RoleScope a : list)
            if (Roles.HQ_ADMIN.equals(a.role()) || Roles.SUPERVISOR.equals(a.role()))
                for (String r : List.of(Roles.LOGISTICS_MGR, Roles.HR_MGR)) {
                    RoleScope x = new RoleScope(r, a.scopeType(), a.scopeId());
                    if (!out.contains(x)) out.add(x);
                }
        return List.copyOf(out);
    }

    public Set<String> roles() {
        Set<String> s = new LinkedHashSet<>();
        assignments.forEach(a -> s.add(a.role()));
        return s;
    }

    public boolean has(String... roles) {
        Set<String> mine = roles();
        for (String r : roles) if (mine.contains(r)) return true;
        return false;
    }

    public boolean isHqAdmin() { return has(Roles.HQ_ADMIN); }

    public void require(String... roles) {
        if (!has(roles)) throw ApiException.forbidden("이 기능을 사용할 권한이 없습니다.");
    }

    /** 특정 등급으로 배정된 범위 목록 */
    public List<RoleScope> scopesOf(String role) {
        return assignments.stream().filter(a -> a.role().equals(role)).toList();
    }
}
