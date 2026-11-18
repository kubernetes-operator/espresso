package kr.espresso.core.store;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/core")
public class StoreController {
    private final JdbcClient db;
    private final StoreScope scope;

    public StoreController(JdbcClient db, StoreScope scope) { this.db = db; this.scope = scope; }

    @GetMapping("/regions")
    public List<Map<String, Object>> regions() {
        CurrentUser.get();
        return db.sql("select id, parent_id, code, name from store.region order by sort_order, code").query().listOfRows();
    }

    /** 내가 볼 수 있는 매장 (요구 16) */
    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        CurrentUser u = CurrentUser.get();
        Set<UUID> ids = scope.allowed(u);
        if (ids.isEmpty()) return List.of();
        return db.sql("""
                select s.id, s.code, s.name, s.status, s.business_type, s.region_id, r.name as region_name,
                       s.vat_display_mode, s.owner_payroll_detail_visible, s.manager_payroll_detail_visible,
                       s.address, s.open_date, s.biz_reg_no, coalesce((s.settings->>'fivePlus')::boolean, true) as five_plus
                from store.store s left join store.region r on r.id = s.region_id
                where s.id in (:ids) order by s.code""").param("ids", ids).query().listOfRows();
    }

    /** 조직도용: 모든 매장 이름 (이름·권역만, 로그인 사용자 누구나) */
    @GetMapping("/stores/names")
    public List<Map<String, Object>> storeNames() {
        CurrentUser.get();
        return db.sql("""
                select s.id, s.code, s.name, s.region_id, r.name as region_name, s.status
                from store.store s left join store.region r on r.id = s.region_id order by s.code""").query().listOfRows();
    }

    @GetMapping("/stores/{id}")
    public Map<String, Object> store(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        scope.check(u, id);
        Map<String, Object> s = db.sql("select * from store.store where id = ?").param(id).query().singleRow();
        s.put("leases", db.sql("select * from store.store_lease where store_id = ? order by start_date desc").param(id).query().listOfRows());
        return s;
    }

    public record StoreSettings(String vatDisplayMode, Boolean ownerPayrollDetailVisible,
                                Boolean managerPayrollDetailVisible, String status, String name,
                                String address, UUID regionId, Boolean fivePlus) {}

    /** 총괄: 매장 설정 (부가세 표시 기본값, 점주 개인별 급여 열람 등) */
    @PutMapping("/stores/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody StoreSettings req) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (req.vatDisplayMode() != null && !Set.of("EXCLUSIVE", "INCLUSIVE").contains(req.vatDisplayMode()))
            throw ApiException.bad("부가세 표시는 EXCLUSIVE / INCLUSIVE 입니다.");
        db.sql("""
                update store.store set vat_display_mode = coalesce(cast(:vat as varchar), vat_display_mode),
                  owner_payroll_detail_visible = coalesce(cast(:own as boolean), owner_payroll_detail_visible),
                  manager_payroll_detail_visible = coalesce(cast(:mgr as boolean), manager_payroll_detail_visible),
                  status = coalesce(cast(:st as varchar), status), name = coalesce(cast(:nm as varchar), name),
                  address = coalesce(cast(:ad as varchar), address), region_id = coalesce(cast(:rg as uuid), region_id),
                  updated_at = now(), updated_by = :by where id = :id""")
                .param("vat", req.vatDisplayMode()).param("own", req.ownerPayrollDetailVisible())
                .param("mgr", req.managerPayrollDetailVisible()).param("st", req.status())
                .param("nm", req.name()).param("ad", req.address()).param("rg", req.regionId())
                .param("by", u.id()).param("id", id).update();
        if (req.fivePlus() != null)
            db.sql("update store.store set settings = settings || jsonb_build_object('fivePlus', ?) where id = ?").params(req.fivePlus(), id).update();
        return Map.of("id", id);
    }
}
