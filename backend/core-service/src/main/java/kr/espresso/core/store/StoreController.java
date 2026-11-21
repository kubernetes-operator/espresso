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
        return db.sql("""
                select r.id, r.parent_id, r.code, r.name, r.level, r.sort_order,
                  (select count(*) from store.store s where s.region_id = r.id) as store_count
                from store.region r order by r.sort_order, r.code""").query().listOfRows();
    }

    public record RegionReq(String code, String name, UUID parentId, Integer sortOrder) {}

    /** 권역 추가 (총괄): 광역(시·도) 또는 하위 권역(예: 강남권) */
    @PostMapping("/regions")
    public Map<String, Object> addRegion(@RequestBody RegionReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        if (r.name() == null || r.name().isBlank()) throw ApiException.bad("권역 이름을 입력하세요.");
        String code = r.code() == null || r.code().isBlank()
                ? "R" + String.format("%04d", db.sql("select count(*) + 1 from store.region").query(Integer.class).single())
                : r.code().trim().toUpperCase();
        if (db.sql("select count(*) from store.region where code = ?").param(code).query(Integer.class).single() > 0) throw ApiException.conflict("이미 있는 권역 코드입니다.");
        UUID id = UUID.randomUUID();
        Integer sort = r.sortOrder() != null ? r.sortOrder() : db.sql("select coalesce(max(sort_order),0) + 1 from store.region").query(Integer.class).single();
        db.sql("insert into store.region(id, parent_id, code, name, level, sort_order) values (?,?,?,?,?,?)")
                .params(id, r.parentId(), code, r.name().trim(), r.parentId() == null ? "SIDO" : "SUB", sort).update();
        return Map.of("id", id, "code", code);
    }

    @PutMapping("/regions/{id}")
    public Map<String, Object> updateRegion(@PathVariable UUID id, @RequestBody RegionReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("update store.region set name = coalesce(cast(? as varchar), name), sort_order = coalesce(cast(? as integer), sort_order) where id = ?")
                .params(r.name(), r.sortOrder(), id).update();
        return Map.of("id", id);
    }

    @DeleteMapping("/regions/{id}")
    public Map<String, Object> deleteRegion(@PathVariable UUID id) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        int used = db.sql("select (select count(*) from store.store where region_id = ?) + (select count(*) from store.region where parent_id = ?)")
                .params(id, id).query(Integer.class).single();
        if (used > 0) throw ApiException.conflict("매장이나 하위 권역이 있는 권역은 삭제할 수 없습니다.");
        db.sql("delete from store.region where id = ?").param(id).update();
        return Map.of("deleted", true);
    }

    /** 내가 볼 수 있는 매장 (요구 16) */
    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        CurrentUser u = CurrentUser.get();
        Set<UUID> ids = scope.allowed(u);
        if (ids.isEmpty()) return List.of();
        return db.sql("""
                select s.id, s.code, s.name, s.status, s.business_type, s.site_type, s.region_id, r.name as region_name,
                       s.vat_display_mode, s.owner_payroll_detail_visible, s.manager_payroll_detail_visible,
                       s.address, s.open_date, s.biz_reg_no, coalesce((s.settings->>'fivePlus')::boolean, true) as five_plus
                from store.store s left join store.region r on r.id = s.region_id
                where s.id in (:ids) order by case s.site_type when 'HQ' then 0 when 'STORE' then 1 else 2 end, s.code""").param("ids", ids).query().listOfRows();
    }

    /** 조직도용: 모든 매장 이름 (이름·권역만, 로그인 사용자 누구나) */
    @GetMapping("/stores/names")
    public List<Map<String, Object>> storeNames() {
        CurrentUser.get();
        return db.sql("""
                select s.id, s.code, s.name, s.region_id, r.name as region_name, s.status, s.site_type
                from store.store s left join store.region r on r.id = s.region_id
                order by case s.site_type when 'HQ' then 0 when 'STORE' then 1 else 2 end, s.code""").query().listOfRows();
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

    // ---------------- 임대 계약 (월세·관리비, 기간 필수, 수정 가능) ----------------
    public record LeaseReq(String lessorName, Long deposit, Long monthlyRent, Long maintenanceFee, String rentVatType,
                           Integer paymentDay, String startDate, String endDate, String memo) {}

    void requireLeaseEditor(CurrentUser u, UUID store) {
        scope.check(u, store);
        if (!u.isHqAdmin() && !scope.hasRoleOn(u, store, Roles.OWNER)) throw ApiException.forbidden("임대 계약은 총괄·점주만 바꿀 수 있습니다.");
    }

    @GetMapping("/stores/{id}/leases")
    public List<Map<String, Object>> leases(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        scope.check(u, id);
        return db.sql("select * from store.store_lease where store_id = ? order by start_date desc").param(id).query().listOfRows();
    }

    void saveLease(UUID leaseId, UUID store, LeaseReq r, boolean create) {
        if (r.startDate() == null || r.startDate().isBlank() || r.endDate() == null || r.endDate().isBlank()) throw ApiException.bad("계약 기간(시작일·종료일)을 입력하세요.");
        java.time.LocalDate a = java.time.LocalDate.parse(r.startDate()), b = java.time.LocalDate.parse(r.endDate());
        if (b.isBefore(a)) throw ApiException.bad("종료일이 시작일보다 빠릅니다.");
        if (r.monthlyRent() == null || r.monthlyRent() < 0) throw ApiException.bad("월세를 입력하세요 (없으면 0).");
        if (r.paymentDay() != null && (r.paymentDay() < 1 || r.paymentDay() > 31)) throw ApiException.bad("납부일은 1~31일입니다.");
        int overlap = db.sql("select count(*) from store.store_lease where store_id = ? and id <> coalesce(cast(? as uuid), gen_random_uuid()) and start_date <= ? and coalesce(end_date, date '9999-12-31') >= ?")
                .params(store, leaseId, b, a).query(Integer.class).single();
        if (overlap > 0) throw ApiException.conflict("기간이 겹치는 임대 계약이 있습니다. 기존 계약의 종료일을 먼저 바꾸세요.");
        String vat = "EXEMPT".equals(r.rentVatType()) ? "EXEMPT" : "TAXABLE";
        if (create)
            db.sql("""
                    insert into store.store_lease(store_id, lessor_name, deposit, monthly_rent, maintenance_fee, rent_vat_type, payment_day, start_date, end_date, memo)
                    values (?,?,?,?,?,?,?,?,?,?)""")
                    .params(store, r.lessorName(), Objects.requireNonNullElse(r.deposit(), 0L), r.monthlyRent(), Objects.requireNonNullElse(r.maintenanceFee(), 0L),
                            vat, r.paymentDay(), a, b, r.memo()).update();
        else
            db.sql("""
                    update store.store_lease set lessor_name = ?, deposit = ?, monthly_rent = ?, maintenance_fee = ?, rent_vat_type = ?, payment_day = ?,
                      start_date = ?, end_date = ?, memo = ? where id = ?""")
                    .params(r.lessorName(), Objects.requireNonNullElse(r.deposit(), 0L), r.monthlyRent(), Objects.requireNonNullElse(r.maintenanceFee(), 0L),
                            vat, r.paymentDay(), a, b, r.memo(), leaseId).update();
    }

    @PostMapping("/stores/{id}/leases")
    public Map<String, Object> addLease(@PathVariable UUID id, @RequestBody LeaseReq r) {
        CurrentUser u = CurrentUser.get();
        requireLeaseEditor(u, id);
        saveLease(null, id, r, true);
        return Map.of("ok", true);
    }

    @PutMapping("/stores/leases/{leaseId}")
    public Map<String, Object> updateLease(@PathVariable UUID leaseId, @RequestBody LeaseReq r) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from store.store_lease where id = ?").param(leaseId).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("임대 계약"));
        requireLeaseEditor(u, store);
        saveLease(leaseId, store, r, false);
        return Map.of("ok", true);
    }

    @DeleteMapping("/stores/leases/{leaseId}")
    public Map<String, Object> deleteLease(@PathVariable UUID leaseId) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from store.store_lease where id = ?").param(leaseId).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("임대 계약"));
        requireLeaseEditor(u, store);
        db.sql("delete from store.store_lease where id = ?").param(leaseId).update();
        return Map.of("ok", true);
    }
}
