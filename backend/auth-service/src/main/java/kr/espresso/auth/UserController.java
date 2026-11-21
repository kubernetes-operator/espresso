package kr.espresso.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.espresso.common.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 계정·등급·조직 관리 */
@RestController
public class UserController {
    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final ObjectMapper om;
    private final String initialPassword;

    public UserController(JdbcClient db, PasswordEncoder encoder, ObjectMapper om,
                          @org.springframework.beans.factory.annotation.Value("${app.initial-password}") String initialPassword) {
        this.db = db; this.encoder = encoder; this.om = om; this.initialPassword = initialPassword;
    }

    public record AssignmentReq(@NotBlank String role, @NotBlank String scopeType, UUID scopeId) {}
    public record CreateUserReq(@NotBlank String loginId, @NotBlank String name,
                                String password, String email, String phone,
                                List<AssignmentReq> assignments) {}
    public record UpdateUserReq(String name, String email, String phone, String status, String vatOverride) {}
    public record PasswordReq(@NotBlank String current, @NotBlank @Size(min = 8) String next) {}

    @GetMapping("/api/auth/roles")
    public List<Map<String, Object>> roles() {
        return db.sql("select code, name, line, rank, parent_code, is_active, description from auth.role order by rank, code")
                .query().listOfRows();
    }

    /** 이름 표시용 디렉터리 (로그인 사용자 누구나) */
    @GetMapping("/api/auth/directory")
    public List<Map<String, Object>> directory() {
        CurrentUser.get();
        return db.sql("select id, login_id, name from auth.app_user order by name").query().listOfRows();
    }

    /**
     * 조직 구성: 등급 트리 + 사용자별 배정.
     *  - 본사 등급(총괄·물류·인사): 전체
     *  - 매장 라인(점주·매장관리자·사원): 본사 인원 + 자기 매장 인원 + 다른 매장의 매장 관리자
     *  - 투자자: 본사 인원 + 투자 매장 인원 (다른 투자자는 제외)
     *  - 투자자 배정 정보는 총괄만 본다 (요구 14)
     */
    @GetMapping("/api/auth/org")
    public Map<String, Object> org() {
        CurrentUser me = CurrentUser.get();
        List<Map<String, Object>> users = usersWithAssignments(null).stream()
                .filter(u -> "ACTIVE".equals(u.get("status")) || me.isHqAdmin()).toList();
        boolean hqView = me.has(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.HR_MGR, Roles.SUPERVISOR);
        if (!hqView) {
            Set<UUID> myStores = new HashSet<>(), investStores = new HashSet<>();
            me.assignments().forEach(a -> {
                if (!"STORE".equals(a.scopeType())) return;
                if (Roles.INVESTOR.equals(a.role())) investStores.add(a.scopeId()); else myStores.add(a.scopeId());
            });
            boolean storeLine = !myStores.isEmpty();
            users = users.stream().filter(u -> {
                if (u.get("id").equals(me.id())) return true;
                for (Map<String, Object> a : asgOf(u)) {
                    String role = (String) a.get("role");
                    if ("HQ".equals(a.get("line"))) return true;
                    if (Roles.INVESTOR.equals(role)) continue;
                    UUID sid = a.get("scopeId") == null ? null : UUID.fromString(a.get("scopeId").toString());
                    if (sid == null) continue;
                    if (myStores.contains(sid) || investStores.contains(sid)) return true;
                    if (storeLine && Roles.STORE_MGR.equals(role)) return true;   // 다른 매장은 매장 관리자만
                }
                return false;
            }).map(u -> {
                if (u.get("id").equals(me.id())) return u;
                // 다른 매장 배정은 매장 관리자만 남김 (점주·사원 배정 숨김)
                Map<String, Object> c = new LinkedHashMap<>(u);
                c.put("assignments", asgOf(u).stream().filter(a -> {
                    if ("HQ".equals(a.get("line"))) return true;
                    UUID sid = a.get("scopeId") == null ? null : UUID.fromString(a.get("scopeId").toString());
                    return sid != null && (myStores.contains(sid) || investStores.contains(sid) || Roles.STORE_MGR.equals(a.get("role")));
                }).toList());
                return c;
            }).toList();
        }
        if (!me.isHqAdmin()) {   // 투자자 정보는 총괄만 (요구 14)
            users = users.stream().filter(u -> u.get("id").equals(me.id())
                    || asgOf(u).stream().anyMatch(a -> !Roles.INVESTOR.equals(a.get("role")))).toList();
            users = users.stream().map(u -> {
                if (u.get("id").equals(me.id())) return u;
                Map<String, Object> c = new LinkedHashMap<>(u);
                c.put("assignments", asgOf(u).stream().filter(a -> !Roles.INVESTOR.equals(a.get("role"))).toList());
                return c;
            }).toList();
        }
        return Map.of("roles", roles(), "users", users);
    }

    @GetMapping("/api/auth/users")
    public List<Map<String, Object>> users(@RequestParam(required = false) UUID storeId) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN, Roles.HR_MGR, Roles.OWNER, Roles.STORE_MGR);
        List<Map<String, Object>> all = usersWithAssignments(storeId);
        if (me.has(Roles.HQ_ADMIN, Roles.HR_MGR)) return all;
        Set<UUID> myStores = new HashSet<>();
        me.assignments().forEach(a -> { if ("STORE".equals(a.scopeType())) myStores.add(a.scopeId()); });
        return all.stream().filter(u -> asgOf(u).stream().anyMatch(a ->
                a.get("scopeId") != null && myStores.contains(UUID.fromString(a.get("scopeId").toString())))).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asgOf(Map<String, Object> u) {
        return (List<Map<String, Object>>) u.get("assignments");
    }

    private List<Map<String, Object>> usersWithAssignments(UUID storeId) {
        List<Map<String, Object>> users = db.sql("""
                select id, login_id, name, email, phone, status, vat_display_override, last_login_at, created_at, initial_password, suspended_at
                from auth.app_user order by name""").query().listOfRows();
        List<Map<String, Object>> asg = db.sql("""
                select a.id, a.user_id, a.role_code, r.name as role_name, r.line, r.rank, r.is_active,
                       a.scope_type, a.scope_id, a.valid_from, a.valid_to
                from auth.user_role_assignment a join auth.role r on r.code = a.role_code
                where a.valid_to is null or a.valid_to >= current_date
                order by r.rank""").query().listOfRows();
        Map<Object, List<Map<String, Object>>> byUser = new HashMap<>();
        for (var a : asg) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.get("id")); m.put("role", a.get("role_code")); m.put("roleName", a.get("role_name"));
            m.put("line", a.get("line")); m.put("rank", a.get("rank")); m.put("roleActive", a.get("is_active"));
            m.put("scopeType", a.get("scope_type")); m.put("scopeId", a.get("scope_id"));
            byUser.computeIfAbsent(a.get("user_id"), k -> new ArrayList<>()).add(m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var u : users) {
            List<Map<String, Object>> my = byUser.getOrDefault(u.get("id"), List.of());
            if (storeId != null && my.stream().noneMatch(a -> storeId.equals(a.get("scopeId")))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.get("id")); m.put("loginId", u.get("login_id")); m.put("name", u.get("name"));
            m.put("email", u.get("email")); m.put("phone", u.get("phone")); m.put("status", u.get("status"));
            m.put("vatOverride", u.get("vat_display_override")); m.put("lastLoginAt", u.get("last_login_at"));
            m.put("initialPassword", u.get("initial_password")); m.put("suspendedAt", u.get("suspended_at"));
            m.put("assignments", my);
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api/auth/users")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateUserReq req) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN, Roles.HR_MGR);
        List<AssignmentReq> asg = req.assignments() == null ? List.of() : req.assignments();
        if (!me.isHqAdmin()) {   // 인사관리자: 매장·베이커리 직원 계정만 생성
            for (AssignmentReq a : asg)
                if (!Set.of(Roles.STAFF, Roles.STORE_MGR).contains(a.role()))
                    throw ApiException.forbidden("인사 관리자는 매장 관리자·일반 사원 계정만 만들 수 있습니다.");
        }
        if (!req.loginId().matches("[A-Za-z0-9._-]{3,64}")) throw ApiException.bad("아이디는 영문·숫자·._- 3자 이상입니다.");
        Integer dup = db.sql("select count(*) from auth.app_user where login_id = ?").param(req.loginId()).query(Integer.class).single();
        if (dup > 0) throw ApiException.conflict("이미 사용 중인 아이디입니다.");
        boolean initial = req.password() == null || req.password().isBlank();
        if (!initial && req.password().length() < 8) throw ApiException.bad("비밀번호는 8자 이상입니다.");
        UUID id = UUID.randomUUID();
        // 비밀번호를 비우면 초기 비밀번호(app.initial-password) — 첫 로그인 후 변경 안내
        db.sql("insert into auth.app_user(id, login_id, name, email, phone, password_hash, initial_password, created_by) values (?,?,?,?,?,?,?,?)")
                .params(id, req.loginId(), req.name(), req.email(), req.phone(), encoder.encode(initial ? initialPassword : req.password()), initial, me.id()).update();
        for (AssignmentReq a : asg) insertAssignment(id, a, me.id());
        audit(me.id(), "USER_CREATE", id, null, Map.of("loginId", req.loginId(), "assignments", asg));
        return Map.of("id", id, "initialPassword", initial);
    }

    /** 비밀번호 초기화 → 초기 비밀번호 (총괄: 전체, 인사관리자: 매장 라인 계정) */
    @PostMapping("/api/auth/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable UUID id) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN, Roles.HR_MGR);
        if (!me.isHqAdmin()) {
            Integer hq = db.sql("select count(*) from auth.user_role_assignment a join auth.role r on r.code = a.role_code where a.user_id = ? and r.line <> 'STORE' and (a.valid_to is null or a.valid_to >= current_date)")
                    .param(id).query(Integer.class).single();
            if (hq > 0) throw ApiException.forbidden("인사 관리자는 매장 라인 계정만 초기화할 수 있습니다.");
        }
        int n = db.sql("update auth.app_user set password_hash = ?, initial_password = true, failed_login_count = 0, updated_at = now(), updated_by = ? where id = ?")
                .params(encoder.encode(initialPassword), me.id(), id).update();
        if (n == 0) throw ApiException.notFound("사용자");
        audit(me.id(), "PASSWORD_RESET", id, null, null);
        return Map.of("reset", true);
    }

    @PutMapping("/api/auth/users/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateUserReq req) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN);
        if (id.equals(me.id()) && req.status() != null && !"ACTIVE".equals(req.status())) throw ApiException.bad("자기 계정은 정지할 수 없습니다.");
        if (req.status() != null && !Set.of("ACTIVE", "SUSPENDED").contains(req.status())) throw ApiException.bad("상태는 ACTIVE(사용) / SUSPENDED(정지) 입니다.");
        if (req.vatOverride() != null && !Set.of("", "EXCLUSIVE", "INCLUSIVE").contains(req.vatOverride()))
            throw ApiException.bad("부가세 표시는 EXCLUSIVE(별도) / INCLUSIVE(포함) / 빈값(매장 기본) 중 하나입니다.");
        var before = db.sql("select name, email, phone, status, vat_display_override from auth.app_user where id = ?")
                .param(id).query().listOfRows();
        if (before.isEmpty()) throw ApiException.notFound("사용자");
        db.sql("""
                update auth.app_user set name = coalesce(cast(? as varchar), name), email = coalesce(cast(? as varchar), email), phone = coalesce(cast(? as varchar), phone),
                  status = coalesce(cast(? as varchar), status),
                  vat_display_override = case when cast(? as varchar) is null then vat_display_override when cast(? as varchar) = '' then null else cast(? as varchar) end,
                  updated_at = now(), updated_by = ? where id = ?""")
                .params(req.name(), req.email(), req.phone(), req.status(),
                        req.vatOverride(), req.vatOverride(), req.vatOverride(), me.id(), id).update();
        // 정지: 삭제하지 않고 상태만 바꿈 → 로그인 차단 + 발급된 토큰도 30초 안에 거부
        if ("SUSPENDED".equals(req.status()))
            db.sql("update auth.app_user set suspended_at = coalesce(suspended_at, now()), suspended_by = ? where id = ?").params(me.id(), id).update();
        if ("ACTIVE".equals(req.status()))
            db.sql("update auth.app_user set suspended_at = null, suspended_by = null, failed_login_count = 0 where id = ?").param(id).update();
        audit(me.id(), "USER_UPDATE", id, before.get(0), req);
        return Map.of("id", id);
    }

    @PostMapping("/api/auth/users/{id}/assignments")
    public Map<String, Object> addAssignment(@PathVariable UUID id, @Valid @RequestBody AssignmentReq req) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN);
        UUID aid = insertAssignment(id, req, me.id());
        audit(me.id(), "ROLE_GRANT", id, null, req);
        return Map.of("id", aid);
    }

    @DeleteMapping("/api/auth/assignments/{assignmentId}")
    public Map<String, Object> revoke(@PathVariable UUID assignmentId) {
        CurrentUser me = CurrentUser.get();
        me.require(Roles.HQ_ADMIN);
        int n = db.sql("update auth.user_role_assignment set valid_to = current_date - 1 where id = ? and valid_to is null")
                .param(assignmentId).update();
        if (n == 0) throw ApiException.notFound("등급 배정");
        audit(me.id(), "ROLE_REVOKE", assignmentId, null, null);
        return Map.of("revoked", true);
    }

    @PutMapping("/api/auth/me/password")
    public Map<String, Object> changePassword(@Valid @RequestBody PasswordReq req) {
        CurrentUser me = CurrentUser.get();
        String hash = db.sql("select password_hash from auth.app_user where id = ?").param(me.id()).query(String.class).single();
        if (!encoder.matches(req.current(), hash)) throw ApiException.bad("현재 비밀번호가 맞지 않습니다.");
        if (req.next().equals(initialPassword)) throw ApiException.bad("초기 비밀번호와 다른 비밀번호를 쓰세요.");
        db.sql("update auth.app_user set password_hash = ?, password_changed_at = now(), initial_password = false where id = ?")
                .params(encoder.encode(req.next()), me.id()).update();
        return Map.of("changed", true);
    }

    // ---------- 내부 API (core/settlement 가 결재 승인자 해석에 사용) ----------

    /** role 이 해당 매장(또는 매장의 권역, 또는 전체)에 배정된 활성 사용자 */
    @GetMapping("/internal/users/approvers")
    public List<UUID> approvers(@RequestParam String role, @RequestParam(required = false) UUID storeId,
                                @RequestParam(required = false) UUID regionId) {
        return db.sql("""
                select distinct a.user_id from auth.user_role_assignment a
                join auth.role r on r.code = a.role_code join auth.app_user u on u.id = a.user_id
                where a.role_code = ? and r.is_active and u.status = 'ACTIVE'
                  and (a.valid_to is null or a.valid_to >= current_date)
                  and (a.scope_type = 'ALL' or (a.scope_type = 'STORE' and a.scope_id = ?)
                       or (a.scope_type = 'REGION' and a.scope_id = ?))""")
                .params(role, storeId, regionId).query(UUID.class).list();
    }

    /** 매장 생성 마법사(core)에서 인원 배정 — 이미 있으면 무시 */
    @PostMapping("/internal/users/{id}/assignments")
    public Map<String, Object> internalGrant(@PathVariable UUID id, @RequestBody AssignmentReq req) {
        Integer dup = db.sql("select count(*) from auth.user_role_assignment where user_id = ? and role_code = ? and scope_type = ? and scope_id is not distinct from ? and valid_to is null")
                .params(id, req.role(), req.scopeType(), req.scopeId()).query(Integer.class).single();
        if (dup > 0) return Map.of("skipped", true);
        UUID aid = insertAssignment(id, req, null);
        audit(null, "ROLE_GRANT_ONBOARDING", id, null, req);
        return Map.of("id", aid);
    }

    /** 직원 이름 변경을 계정 이름에도 반영 (core 인사 → auth) */
    @PutMapping("/internal/users/{id}/name")
    public Map<String, Object> rename(@PathVariable UUID id, @RequestBody Map<String, String> b) {
        String name = b.get("name");
        if (name == null || name.isBlank()) throw ApiException.bad("이름을 입력하세요.");
        var before = db.sql("select name from auth.app_user where id = ?").param(id).query(String.class).optional();
        if (before.isEmpty()) throw ApiException.notFound("사용자");
        if (before.get().equals(name.trim())) return Map.of("changed", false);
        db.sql("update auth.app_user set name = ?, updated_at = now() where id = ?").params(name.trim(), id).update();
        audit(null, "USER_RENAME_FROM_HR", id, Map.of("name", before.get()), Map.of("name", name.trim()));
        return Map.of("changed", true);
    }

    /** 각 서비스의 토큰 검증에서 계정 상태 확인 (정지 계정 차단) */
    @GetMapping("/internal/users/{id}/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        String st = db.sql("select status from auth.app_user where id = ?").param(id).query(String.class).optional().orElse("MISSING");
        return Map.of("status", st);
    }

    @GetMapping("/internal/users/{id}")
    public Map<String, Object> internalUser(@PathVariable UUID id) {
        var rows = db.sql("select id, login_id, name, vat_display_override from auth.app_user where id = ?").param(id).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("사용자");
        return rows.get(0);
    }

    private UUID insertAssignment(UUID userId, AssignmentReq a, UUID actor) {
        if (!Set.of("ALL", "REGION", "STORE").contains(a.scopeType())) throw ApiException.bad("scopeType 은 ALL/REGION/STORE 입니다.");
        if ("ALL".equals(a.scopeType()) != (a.scopeId() == null)) throw ApiException.bad("ALL 이 아니면 scopeId 가 필요합니다.");
        UUID aid = UUID.randomUUID();
        db.sql("insert into auth.user_role_assignment(id, user_id, role_code, scope_type, scope_id, created_by) values (?,?,?,?,?,?)")
                .params(aid, userId, a.role(), a.scopeType(), a.scopeId(), actor).update();
        return aid;
    }

    private void audit(UUID actor, String action, UUID target, Object before, Object after) {
        try {
            db.sql("insert into auth.audit_log(actor_id, action, target_type, target_id, before_data, after_data) values (?,?,?,?,?::jsonb,?::jsonb)")
                    .params(actor, action, "USER", target,
                            before == null ? null : om.writeValueAsString(before),
                            after == null ? null : om.writeValueAsString(after)).update();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
