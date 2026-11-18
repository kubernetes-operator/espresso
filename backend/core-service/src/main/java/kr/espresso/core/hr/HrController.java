package kr.espresso.core.hr;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 직원·계약·근무기록·출퇴근 (요구 25). 개인정보(주민번호·계좌)는 데모에서 입력받지 않음 */
@RestController
@RequestMapping("/api/core/hr")
public class HrController {
    static final String[] MANAGE = {Roles.HR_MGR, Roles.HQ_ADMIN};
    static final String[] VIEW = {Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR};
    private final JdbcClient db;
    private final StoreScope scope;

    public HrController(JdbcClient db, StoreScope scope) { this.db = db; this.scope = scope; }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam UUID storeId) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, VIEW);
        boolean money = scope.hasRoleOn(u, storeId, Roles.HR_MGR, Roles.HQ_ADMIN) || payrollVisible(u, storeId);
        return db.sql("""
                select e.id, e.user_id, e.store_id, e.emp_no, e.name, e.hire_date, e.resign_date, e.status,
                       c.id contract_id, c.employment_type, c.pay_type, c.base_wage, c.weekly_contract_hours, c.weekly_holiday_pay, c.income_tax_type,
                       c.national_pension, c.health_insurance, c.employment_insurance, c.tax_dependents, cast(c.non_taxable_allowances as text) allowances, c.valid_from
                from hr.employee e left join lateral (select * from hr.employment_contract c where c.employee_id = e.id and c.valid_to is null order by valid_from desc limit 1) c on true
                where e.store_id = ? order by e.status, e.emp_no""").param(storeId).query().listOfRows()
                .stream().map(r -> { if (!money) { Map<String, Object> m = new LinkedHashMap<>(r); m.put("base_wage", null); return m; } return r; }).toList();
    }

    /** 점주·매장관리자의 개인별 급여 열람 허용 여부 (매장 설정) */
    boolean payrollVisible(CurrentUser u, UUID storeId) {
        var s = db.sql("select owner_payroll_detail_visible o, manager_payroll_detail_visible m from store.store where id = ?").param(storeId).query().singleRow();
        return (Boolean.TRUE.equals(s.get("o")) && scope.hasRoleOn(u, storeId, Roles.OWNER))
                || (Boolean.TRUE.equals(s.get("m")) && scope.hasRoleOn(u, storeId, Roles.STORE_MGR));
    }

    public record EmployeeReq(UUID storeId, UUID userId, String name, String hireDate, String employmentType, String payType, Long baseWage,
                              BigDecimal weeklyHours, Boolean weeklyHolidayPay, String incomeTaxType, Boolean nationalPension, Boolean healthInsurance,
                              Boolean employmentInsurance, Integer taxDependents, Long mealAllowance, String status, String resignDate) {}

    @PostMapping("/employees")
    @Transactional
    public Map<String, Object> create(@RequestBody EmployeeReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), MANAGE);
        if (r.name() == null || r.name().isBlank() || r.baseWage() == null) throw ApiException.bad("이름과 급여는 필수입니다.");
        UUID id = UUID.randomUUID();
        Integer n = db.sql("select count(*) from hr.employee").query(Integer.class).single();
        db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, hire_date) values (?,?,?,?,?, cast(? as date))")
                .params(id, r.userId(), r.storeId(), String.format("E%05d", n + 1), r.name().trim(), Objects.requireNonNullElse(r.hireDate(), LocalDate.now().toString())).update();
        contract(id, r, LocalDate.parse(Objects.requireNonNullElse(r.hireDate(), LocalDate.now().toString())));
        return Map.of("id", id);
    }

    /** 계약 변경: 기존 계약 종료 + 새 계약 (이력 보존) */
    @PutMapping("/employees/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody EmployeeReq r) {
        CurrentUser u = CurrentUser.get();
        var e = db.sql("select store_id from hr.employee where id = ?").param(id).query(UUID.class).single();
        scope.requireRoleOn(u, e, MANAGE);
        db.sql("update hr.employee set name = coalesce(cast(? as varchar), name), status = coalesce(cast(? as varchar), status), resign_date = cast(? as date), user_id = coalesce(cast(? as uuid), user_id), updated_at = now() where id = ?")
                .params(r.name(), r.status(), r.resignDate(), r.userId(), id).update();
        if (r.baseWage() != null) {
            LocalDate from = LocalDate.now();
            db.sql("delete from hr.employment_contract where employee_id = ? and valid_from = ? and not exists (select 1 from hr.payslip p where p.contract_id = hr.employment_contract.id)").params(id, from).update();
            db.sql("update hr.employment_contract set valid_to = ? where employee_id = ? and valid_to is null").params(from.minusDays(1), id).update();
            contract(id, r, from);
        }
        return Map.of("id", id);
    }

    void contract(UUID emp, EmployeeReq r, LocalDate from) {
        String type = Objects.requireNonNullElse(r.employmentType(), "PART_TIME");
        boolean free = "FREELANCER".equals(type);
        db.sql("""
                insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, weekly_holiday_pay, income_tax_type,
                  national_pension, health_insurance, employment_insurance, tax_dependents, non_taxable_allowances, valid_from)
                values (?,?,?,?,?,?,?,?,?,?,?, cast(? as jsonb), ?)""")
                .params(emp, type, Objects.requireNonNullElse(r.payType(), "HOURLY"), r.baseWage(), Objects.requireNonNullElse(r.weeklyHours(), new BigDecimal("20")),
                        r.weeklyHolidayPay() == null || r.weeklyHolidayPay(), free ? "BIZ_3_3" : Objects.requireNonNullElse(r.incomeTaxType(), "WAGE"),
                        !free && (r.nationalPension() == null || r.nationalPension()), !free && (r.healthInsurance() == null || r.healthInsurance()),
                        !free && (r.employmentInsurance() == null || r.employmentInsurance()), Objects.requireNonNullElse(r.taxDependents(), 1),
                        r.mealAllowance() == null || r.mealAllowance() == 0 ? "{}" : "{\"meal\":" + r.mealAllowance() + "}", from).update();
    }

    // ---------------- 근무기록 ----------------
    @GetMapping("/work")
    public List<Map<String, Object>> work(@RequestParam UUID storeId, @RequestParam String month, @RequestParam(required = false) UUID employeeId) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, VIEW);
        YearMonth ym = YearMonth.parse(month);
        return db.sql("""
                select w.*, e.name employee_name from hr.work_record w join hr.employee e on e.id = w.employee_id
                where w.store_id = ? and w.work_date between ? and ? and (cast(? as uuid) is null or w.employee_id = ?) order by w.work_date desc, e.name""")
                .params(storeId, ym.atDay(1), ym.atEndOfMonth(), employeeId, employeeId).query().listOfRows();
    }

    public record WorkReq(UUID employeeId, String workDate, String clockIn, String clockOut, Integer breakMinutes, Integer holidayMinutes, String memo) {}

    /** 수기 근무기록 (인적관리자·매장관리자·점주) — 시각은 KST "HH:mm" */
    @PostMapping("/work")
    @Transactional
    public Map<String, Object> addWork(@RequestBody WorkReq r) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from hr.employee where id = ?").param(r.employeeId()).query(UUID.class).single();
        scope.requireRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR);
        LocalDate d = LocalDate.parse(r.workDate());
        OffsetDateTime in = at(d, r.clockIn()), out = at(d, r.clockOut());
        if (out != null && in != null && !out.isAfter(in)) out = out.plusDays(1);   // 자정 넘김
        var m = WorkCalc.split(in, out, Objects.requireNonNullElse(r.breakMinutes(), 0));
        UUID id = UUID.randomUUID();
        db.sql("""
                insert into hr.work_record(id, employee_id, store_id, work_date, clock_in, clock_out, break_minutes, regular_minutes, overtime_minutes, night_minutes, holiday_minutes, source, confirmed_by, confirmed_at, memo)
                values (?,?,?,?,?,?,?,?,?,?,?, 'MANUAL', ?, now(), ?)""")
                .params(id, r.employeeId(), store, d, in, out, Objects.requireNonNullElse(r.breakMinutes(), 0), m.regular(), m.overtime(), m.night(),
                        Objects.requireNonNullElse(r.holidayMinutes(), 0), u.id(), r.memo()).update();
        return Map.of("id", id);
    }

    @DeleteMapping("/work/{id}")
    public Map<String, Object> deleteWork(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from hr.work_record where id = ?").param(id).query(UUID.class).single();
        scope.requireRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.STORE_MGR);
        db.sql("delete from hr.work_record where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    @PostMapping("/work/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from hr.work_record where id = ?").param(id).query(UUID.class).single();
        scope.requireRoleOn(u, store, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR);
        db.sql("update hr.work_record set confirmed_by = ?, confirmed_at = now() where id = ?").params(u.id(), id).update();
        return Map.of("ok", true);
    }

    static OffsetDateTime at(LocalDate d, String hm) {
        if (hm == null || hm.isBlank()) return null;
        return d.atTime(LocalTime.parse(hm)).atZone(WorkCalc.KST).toOffsetDateTime();
    }

    // ---------------- 내 근무·출퇴근·급여명세 ----------------
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser u = CurrentUser.get();
        var emps = db.sql("select e.*, s.name store_name from hr.employee e join store.store s on s.id = e.store_id where e.user_id = ? and e.status <> 'RESIGNED'").param(u.id()).query().listOfRows();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employees", emps);
        List<UUID> ids = emps.stream().map(e -> (UUID) e.get("id")).toList();
        if (ids.isEmpty()) { m.put("records", List.of()); m.put("payslips", List.of()); m.put("open", null); return m; }
        m.put("open", db.sql("select * from hr.work_record where employee_id in (:ids) and clock_out is null and source = 'APP' order by clock_in desc limit 1").param("ids", ids).query().listOfRows().stream().findFirst().orElse(null));
        m.put("records", db.sql("select * from hr.work_record where employee_id in (:ids) order by work_date desc, clock_in desc limit 40").param("ids", ids).query().listOfRows());
        m.put("payslips", db.sql("""
                select p.id, r.period, p.gross_pay, p.total_deduction, p.net_pay, p.published_at, p.viewed_at, s.name store_name
                from hr.payslip p join hr.payroll_run r on r.id = p.payroll_run_id join store.store s on s.id = r.store_id
                where p.employee_id in (:ids) and p.published_at is not null order by r.period desc""").param("ids", ids).query().listOfRows());
        return m;
    }

    @PostMapping("/clock-in")
    @Transactional
    public Map<String, Object> clockIn() {
        CurrentUser u = CurrentUser.get();
        var e = db.sql("select id, store_id from hr.employee where user_id = ? and status = 'ACTIVE' limit 1").param(u.id()).query().listOfRows();
        if (e.isEmpty()) throw ApiException.bad("직원으로 등록되어 있지 않습니다. 인적 관리자에게 문의하세요.");
        UUID emp = (UUID) e.get(0).get("id");
        if (db.sql("select count(*) from hr.work_record where employee_id = ? and clock_out is null and source = 'APP'").param(emp).query(Integer.class).single() > 0)
            throw ApiException.conflict("이미 출근 상태입니다.");
        OffsetDateTime now = OffsetDateTime.now(WorkCalc.KST);
        UUID id = UUID.randomUUID();
        db.sql("insert into hr.work_record(id, employee_id, store_id, work_date, clock_in, source) values (?,?,?,?,?, 'APP')")
                .params(id, emp, e.get(0).get("store_id"), now.toLocalDate(), now).update();
        return Map.of("id", id, "clockIn", now.toString());
    }

    @PostMapping("/clock-out")
    @Transactional
    public Map<String, Object> clockOut(@RequestBody(required = false) Map<String, Integer> body) {
        CurrentUser u = CurrentUser.get();
        var r = db.sql("""
                select w.* from hr.work_record w join hr.employee e on e.id = w.employee_id
                where e.user_id = ? and w.clock_out is null and w.source = 'APP' order by w.clock_in desc limit 1""").param(u.id()).query().listOfRows();
        if (r.isEmpty()) throw ApiException.conflict("출근 기록이 없습니다.");
        int brk = body == null ? 0 : Objects.requireNonNullElse(body.get("breakMinutes"), 0);
        OffsetDateTime in = ((java.sql.Timestamp) r.get(0).get("clock_in")).toInstant().atOffset(ZoneOffset.ofHours(9)), out = OffsetDateTime.now(WorkCalc.KST);
        var m = WorkCalc.split(in, out, brk);
        db.sql("update hr.work_record set clock_out = ?, break_minutes = ?, regular_minutes = ?, overtime_minutes = ?, night_minutes = ? where id = ?")
                .params(out, brk, m.regular(), m.overtime(), m.night(), r.get(0).get("id")).update();
        return Map.of("regularMinutes", m.regular(), "overtimeMinutes", m.overtime());
    }
}
