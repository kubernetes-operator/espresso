package kr.espresso.core.support;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.RoleScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/** 요구 16: 로그인 사용자가 볼 수 있는 매장 범위 계산 (등급 배정의 ALL/REGION/STORE 해석) */
@Component
public class StoreScope {
    private final JdbcClient db;
    public StoreScope(JdbcClient db) { this.db = db; }

    public Set<UUID> allowed(CurrentUser u) { return allowed(u, null); }

    /** roleFilter 가 주어지면 해당 등급으로 배정된 범위만 */
    public Set<UUID> allowed(CurrentUser u, Collection<String> roleFilter) {
        Set<UUID> out = new LinkedHashSet<>();
        for (RoleScope a : u.assignments()) {
            if (roleFilter != null && !roleFilter.contains(a.role())) continue;
            // 투자자는 운영 데이터 범위에서 제외 (투자 화면만 별도 허용)
            if (roleFilter == null && "INVESTOR".equals(a.role())) continue;
            switch (a.scopeType()) {
                case "ALL" -> { return new LinkedHashSet<>(db.sql("select id from store.store order by code").query(UUID.class).list()); }
                case "REGION" -> out.addAll(db.sql("""
                        with recursive r as (select id from store.region where id = ?
                          union all select c.id from store.region c join r on c.parent_id = r.id)
                        select s.id from store.store s where s.region_id in (select id from r)""")
                        .param(a.scopeId()).query(UUID.class).list());
                case "STORE" -> out.add(a.scopeId());
                default -> { }
            }
        }
        return out;
    }

    public void check(CurrentUser u, UUID storeId) {
        if (storeId == null || !allowed(u).contains(storeId)) throw ApiException.forbidden("이 매장에 접근할 권한이 없습니다.");
    }

    /** 해당 매장에 대해 지정 등급 중 하나를 가지고 있는지 */
    public boolean hasRoleOn(CurrentUser u, UUID storeId, String... roles) {
        return allowed(u, List.of(roles)).contains(storeId);
    }

    public void requireRoleOn(CurrentUser u, UUID storeId, String... roles) {
        if (!hasRoleOn(u, storeId, roles)) throw ApiException.forbidden("이 매장에서 이 작업을 할 권한이 없습니다.");
    }

    public UUID regionOf(UUID storeId) {
        return db.sql("select region_id from store.store where id = ?").param(storeId).query(UUID.class).optional().orElse(null);
    }
}
