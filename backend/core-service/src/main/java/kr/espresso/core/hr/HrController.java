package kr.espresso.core.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kr.espresso.common.*;
import kr.espresso.core.support.AuthClient;
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
    static final String[] SCHEDULE_EDIT = {Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR};
    private final JdbcClient db;
    private final StoreScope scope;
    private final ObjectMapper om;
    private final AuthClient auth;
    private final HttpServletRequest http;

    public HrController(JdbcClient db, StoreScope scope, ObjectMapper om, AuthClient auth, HttpServletRequest http) {
        this.db = db; this.scope = scope; this.om = om; this.auth = auth; this.http = http;
    }

    /** 매장 지정 시 그 매장, 없으면 볼 수 있는 모든 사이트(전체 합산 화면) */
    Set<UUID> sites(CurrentUser u, UUID storeId, String... roles) {
        if (storeId != null) { scope.requireRoleOn(u, storeId, roles); return Set.of(storeId); }
        return scope.allowed(u, List.of(roles));
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = sites(u, storeId, VIEW);
        if (st.isEmpty()) return List.of();
        Set<UUID> moneyStores = new HashSet<>();
        for (UUID s : st) if (scope.hasRoleOn(u, s, Roles.HR_MGR, Roles.HQ_ADMIN) || payrollVisible(u, s)) moneyStores.add(s);
        return db.sql("""
                select e.id, e.user_id, e.store_id, s.name store_name, s.site_type, e.emp_no, e.name, e.job_title, e.hire_date, e.resign_date, e.status,
                       c.id contract_id, c.employment_type, c.pay_type, c.base_wage, c.salary_grade, g.annual_salary, g.group_name grade_group_name,
                       c.weekly_contract_hours, c.weekly_holiday_pay, c.income_tax_type,
                       c.national_pension, c.health_insurance, c.employment_insurance, c.tax_dependents, cast(c.non_taxable_allowances as text) allowances, c.valid_from
                from hr.employee e join store.store s on s.id = e.store_id
                left join lateral (select * from hr.employment_contract c where c.employee_id = e.id and c.valid_to is null order by valid_from desc limit 1) c on true
                left join hr.salary_grade g on g.code = c.salary_grade
                where e.store_id in (:st) order by case s.site_type when 'HQ' then 0 when 'STORE' then 1 else 2 end, s.code, e.status, e.emp_no""")
                .param("st", st).query().listOfRows()
                .stream().map(r -> {
                    if (moneyStores.contains((UUID) r.get("store_id"))) return r;
                    Map<String, Object> m = new LinkedHashMap<>(r); m.put("base_wage", null); m.put("annual_salary", null); m.put("allowances", null); return m;
                }).toList();
    }

    /** 점주·매장관리자의 개인별 급여 열람 허용 여부 (매장 설정) */
    boolean payrollVisible(CurrentUser u, UUID storeId) {
        var s = db.sql("select owner_payroll_detail_visible o, manager_payroll_detail_visible m from store.store where id = ?").param(storeId).query().singleRow();
        return (Boolean.TRUE.equals(s.get("o")) && scope.hasRoleOn(u, storeId, Roles.OWNER))
                || (Boolean.TRUE.equals(s.get("m")) && scope.hasRoleOn(u, storeId, Roles.STORE_MGR));
    }

    /** 기타 비과세 항목 (예: 자가운전보조금, 출산·보육수당) */
    public record Allowance(String name, Long amount) {}
    public record EmployeeReq(UUID storeId, UUID userId, String name, String hireDate, String employmentType, String payType, Long baseWage,
                              BigDecimal weeklyHours, Boolean weeklyHolidayPay, String incomeTaxType, Boolean nationalPension, Boolean healthInsurance,
                              Boolean employmentInsurance, Integer taxDependents, Long mealAllowance, String status, String resignDate,
                              String salaryGrade, List<Allowance> otherAllowances, String jobTitle) {}

    @PostMapping("/employees")
    @Transactional
    public Map<String, Object> create(@RequestBody EmployeeReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), MANAGE);
        return insertEmployee(r);
    }

    /** 사번: 00000001 부터 8자리 연번 */
    String nextEmpNo() { return String.format("%08d", db.sql("select nextval('hr.emp_no_seq')").query(Long.class).single()); }

    Map<String, Object> insertEmployee(EmployeeReq r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.bad("이름은 필수입니다.");
        if (r.baseWage() == null && (r.salaryGrade() == null || r.salaryGrade().isBlank())) throw ApiException.bad("연봉 등급 또는 급여(시급·월급)를 입력하세요.");
        if (r.userId() != null && db.sql("select count(*) from hr.employee where user_id = ? and status <> 'RESIGNED'").param(r.userId()).query(Integer.class).single() > 0)
            throw ApiException.conflict("이 계정은 이미 직원으로 연결되어 있습니다.");
        UUID id = UUID.randomUUID();
        String empNo = nextEmpNo();
        String hire = r.hireDate() == null || r.hireDate().isBlank() ? LocalDate.now().toString() : r.hireDate();
        db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, job_title, hire_date) values (?,?,?,?,?,?, cast(? as date))")
                .params(id, r.userId(), r.storeId(), empNo, r.name().trim(), r.jobTitle(), hire).update();
        contract(id, r, LocalDate.parse(hire));
        return Map.of("id", id, "empNo", empNo);
    }

    /** 계약 변경: 기존 계약 종료 + 새 계약 (이력 보존) */
    @PutMapping("/employees/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody EmployeeReq r) {
        CurrentUser u = CurrentUser.get();
        var e = db.sql("select store_id from hr.employee where id = ?").param(id).query(UUID.class).single();
        scope.requireRoleOn(u, e, MANAGE);
        db.sql("update hr.employee set name = coalesce(cast(? as varchar), name), status = coalesce(cast(? as varchar), status), resign_date = cast(? as date), user_id = coalesce(cast(? as uuid), user_id), job_title = coalesce(cast(? as varchar), job_title), updated_at = now() where id = ?")
                .params(r.name(), r.status(), blank(r.resignDate()), r.userId(), r.jobTitle(), id).update();
        if (r.baseWage() != null || (r.salaryGrade() != null && !r.salaryGrade().isBlank())) {
            LocalDate from = LocalDate.now();
            db.sql("delete from hr.employment_contract where employee_id = ? and valid_from = ? and not exists (select 1 from hr.payslip p where p.contract_id = hr.employment_contract.id)").params(id, from).update();
            db.sql("update hr.employment_contract set valid_to = ? where employee_id = ? and valid_to is null").params(from.minusDays(1), id).update();
            contract(id, r, from);
        }
        return Map.of("id", id);
    }

    static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    void contract(UUID emp, EmployeeReq r, LocalDate from) {
        String grade = blank(r.salaryGrade());
        Long wage = r.baseWage();
        String payType = Objects.requireNonNullElse(r.payType(), "HOURLY");
        if (grade != null) {   // 연봉 테이블 적용: 월급 = 연봉 / 12 (원 미만 절사)
            Long annual = db.sql("select annual_salary from hr.salary_grade where code = ?").param(grade).query(Long.class).optional()
                    .orElseThrow(() -> ApiException.bad("없는 연봉 등급입니다: " + grade));
            wage = annual / 12; payType = "MONTHLY";
        }
        String type = Objects.requireNonNullElse(r.employmentType(), grade != null ? "REGULAR" : "PART_TIME");
        boolean free = "FREELANCER".equals(type);
        Map<String, Object> allow = new LinkedHashMap<>();
        if (r.mealAllowance() != null && r.mealAllowance() > 0) allow.put("meal", r.mealAllowance());
        List<Map<String, Object>> etc = new ArrayList<>();
        if (r.otherAllowances() != null) for (Allowance a : r.otherAllowances())
            if (a.name() != null && !a.name().isBlank() && a.amount() != null && a.amount() > 0) etc.add(Map.of("name", a.name().trim(), "amount", a.amount()));
        if (!etc.isEmpty()) allow.put("etc", etc);
        String allowJson;
        try { allowJson = om.writeValueAsString(allow); } catch (Exception e) { allowJson = "{}"; }
        db.sql("""
                insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, weekly_holiday_pay, income_tax_type,
                  national_pension, health_insurance, employment_insurance, tax_dependents, non_taxable_allowances, valid_from, salary_grade)
                values (?,?,?,?,?,?,?,?,?,?,?, cast(? as jsonb), ?, ?)""")
                .params(emp, type, payType, wage, Objects.requireNonNullElse(r.weeklyHours(), new BigDecimal("40")),
                        r.weeklyHolidayPay() == null || r.weeklyHolidayPay(), free ? "BIZ_3_3" : Objects.requireNonNullElse(r.incomeTaxType(), "WAGE"),
                        !free && (r.nationalPension() == null || r.nationalPension()), !free && (r.healthInsurance() == null || r.healthInsurance()),
                        !free && (r.employmentInsurance() == null || r.employmentInsurance()), Objects.requireNonNullElse(r.taxDependents(), 1),
                        allowJson, from, grade).update();
    }

    // ---------------- 근무기록 ----------------
    @GetMapping("/work")
    public List<Map<String, Object>> work(@RequestParam(required = false) UUID storeId, @RequestParam String month, @RequestParam(required = false) UUID employeeId) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = sites(u, storeId, VIEW);
        if (st.isEmpty()) return List.of();
        YearMonth ym = YearMonth.parse(month);
        return db.sql("""
                select w.*, e.name employee_name, s.name store_name from hr.work_record w join hr.employee e on e.id = w.employee_id join store.store s on s.id = w.store_id
                where w.store_id in (:st) and w.work_date between :f and :t and (cast(:e as uuid) is null or w.employee_id = :e) order by w.work_date desc, s.code, e.name""")
                .param("st", st).param("f", ym.atDay(1)).param("t", ym.atEndOfMonth()).param("e", employeeId).query().listOfRows();
    }

    public record WorkReq(UUID employeeId, String workDate, String clockIn, String clockOut, Integer breakMinutes, Integer holidayMinutes, String memo) {}

    /** 수기 근무기록 (인사관리자·매장관리자·점주) — 시각은 KST "HH:mm" */
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
        if (e.isEmpty()) throw ApiException.bad("직원으로 등록되어 있지 않습니다. 인사 관리자에게 문의하세요.");
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

    // ---------------- 근무표 (매장별 계획) ----------------
    @GetMapping("/schedule")
    public Map<String, Object> schedule(@RequestParam UUID storeId, @RequestParam String from, @RequestParam String to) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employees", db.sql("select id, emp_no, name, job_title from hr.employee where store_id = ? and status = 'ACTIVE' order by emp_no").param(storeId).query().listOfRows());
        m.put("items", db.sql("""
                select s.id, s.employee_id, s.work_date, to_char(s.start_time, 'HH24:MI') start_time, to_char(s.end_time, 'HH24:MI') end_time, s.memo
                from hr.work_schedule s where s.store_id = ? and s.work_date between cast(? as date) and cast(? as date) order by s.work_date, s.start_time""")
                .params(storeId, from, to).query().listOfRows());
        m.put("canEdit", scope.hasRoleOn(u, storeId, SCHEDULE_EDIT));
        return m;
    }

    public record ScheduleReq(UUID employeeId, String workDate, String startTime, String endTime, String memo) {}

    @PutMapping("/schedule")
    public Map<String, Object> putSchedule(@RequestBody ScheduleReq r) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from hr.employee where id = ?").param(r.employeeId()).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("직원"));
        scope.requireRoleOn(u, store, SCHEDULE_EDIT);
        LocalTime a = LocalTime.parse(r.startTime()), b = LocalTime.parse(r.endTime());
        if (a.equals(b)) throw ApiException.bad("시작·종료 시각이 같습니다.");
        db.sql("""
                insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, memo, created_by) values (?,?, cast(? as date), ?, ?, ?, ?)
                on conflict (employee_id, work_date) do update set start_time = excluded.start_time, end_time = excluded.end_time, memo = excluded.memo""")
                .params(store, r.employeeId(), r.workDate(), a, b, blank(r.memo()), u.id()).update();
        return Map.of("ok", true);
    }

    @DeleteMapping("/schedule/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        UUID store = db.sql("select store_id from hr.work_schedule where id = ?").param(id).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("근무표"));
        scope.requireRoleOn(u, store, SCHEDULE_EDIT);
        db.sql("delete from hr.work_schedule where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    public record CopyWeekReq(UUID storeId, String fromMonday, String toMonday) {}

    /** 지난주 근무표를 이번 주로 복사 (이미 있는 날은 유지) */
    @PostMapping("/schedule/copy-week")
    public Map<String, Object> copyWeek(@RequestBody CopyWeekReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), SCHEDULE_EDIT);
        LocalDate f = LocalDate.parse(r.fromMonday()), t = LocalDate.parse(r.toMonday());
        int n = db.sql("""
                insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, memo, created_by)
                select s.store_id, s.employee_id, s.work_date + ?, s.start_time, s.end_time, s.memo, ? from hr.work_schedule s
                join hr.employee e on e.id = s.employee_id and e.status = 'ACTIVE'
                where s.store_id = ? and s.work_date between ? and ? on conflict (employee_id, work_date) do nothing""")
                .params((int) (t.toEpochDay() - f.toEpochDay()), u.id(), r.storeId(), f, f.plusDays(6)).update();
        return Map.of("copied", n);
    }

    // ---------------- 연봉 테이블 ----------------
    @GetMapping("/salary-grades")
    public List<Map<String, Object>> grades() {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.HR_MGR);
        return db.sql("select code, grade_group, group_name, step, annual_salary, annual_salary / 12 as monthly, memo, updated_at from hr.salary_grade order by grade_group, step")
                .query().listOfRows();
    }

    public record GradeReq(Long annualSalary, String memo) {}

    /** 등급 연봉 변경 → 이 등급을 쓰는 직원의 급여 계산에 바로 반영 */
    @PutMapping("/salary-grades/{code}")
    public Map<String, Object> setGrade(@PathVariable String code, @RequestBody GradeReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.HR_MGR);
        if (r.annualSalary() == null || r.annualSalary() < 0) throw ApiException.bad("연봉을 입력하세요.");
        int n = db.sql("update hr.salary_grade set annual_salary = ?, memo = coalesce(cast(? as text), memo), updated_at = now() where code = ?").params(r.annualSalary(), r.memo(), code).update();
        if (n == 0) throw ApiException.notFound("연봉 등급");
        db.sql("update hr.employment_contract set base_wage = ? where salary_grade = ? and valid_to is null").params(r.annualSalary() / 12, code).update();
        return Map.of("ok", true);
    }

    public record GradeGroupReq(String prefix, String name, Long base, Long increment) {}

    /** 직급군 추가: 접두어 + 20단계 (예: b 베이커 → b1~b20) */
    @PostMapping("/salary-grades/groups")
    public Map<String, Object> addGroup(@RequestBody GradeGroupReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN, Roles.HR_MGR);
        String p = r.prefix() == null ? "" : r.prefix().trim().toLowerCase();
        if (!p.matches("[a-z]{1,3}")) throw ApiException.bad("접두어는 영문 소문자 1~3자입니다.");
        if (r.name() == null || r.name().isBlank() || r.base() == null || r.base() < 0) throw ApiException.bad("직급군 이름과 1단계 연봉을 입력하세요.");
        if (db.sql("select count(*) from hr.salary_grade where grade_group = ?").param(p).query(Integer.class).single() > 0) throw ApiException.conflict("이미 있는 접두어입니다.");
        long inc = Objects.requireNonNullElse(r.increment(), 0L);
        for (int i = 1; i <= 20; i++)
            db.sql("insert into hr.salary_grade(code, grade_group, group_name, step, annual_salary) values (?,?,?,?,?)").params(p + i, p, r.name().trim(), i, r.base() + (i - 1) * inc).update();
        return Map.of("ok", true);
    }

    // ---------------- 계정 + 직원 동시 등록 ----------------
    public record AccountEmployee(Boolean register, String employmentType, String payType, Long baseWage, String salaryGrade,
                                  BigDecimal weeklyHours, String hireDate, String jobTitle) {}
    public record AccountReq(String loginId, String name, String email, String phone, String password,
                             String site, String role, String scopeType, UUID scopeId, AccountEmployee employee) {}

    /**
     * 계정 만들기: auth 에 계정(초기 비밀번호) 생성 → 같은 정보로 직원 등록(사번 자동).
     * site: HQ(본사, 직원 소속 = 본사 사이트) / STORE(매장) / BAKERY(베이커리)
     */
    @PostMapping("/accounts")
    @Transactional
    public Map<String, Object> createAccount(@RequestBody AccountReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.HR_MGR);
        String site = Objects.requireNonNullElse(r.site(), "STORE");
        UUID empSite;
        if ("HQ".equals(site)) {
            if (!Set.of(Roles.HQ_ADMIN, Roles.LOGISTICS_MGR, Roles.HR_MGR, Roles.SUPERVISOR).contains(r.role())) throw ApiException.bad("본사 소속은 본사 등급만 고를 수 있습니다.");
            empSite = Sites.HQ;
        } else {
            if (r.scopeId() == null) throw ApiException.bad(("BAKERY".equals(site) ? "베이커리" : "매장") + "를 고르세요.");
            String t = db.sql("select site_type from store.store where id = ?").param(r.scopeId()).query(String.class).optional().orElseThrow(() -> ApiException.notFound("매장"));
            if (!t.equals(site)) throw ApiException.bad("소속 구분과 매장 종류가 맞지 않습니다.");
            if (!Set.of(Roles.OWNER, Roles.STORE_MGR, Roles.STAFF, Roles.INVESTOR).contains(r.role())) throw ApiException.bad("매장·베이커리 소속은 매장 등급만 고를 수 있습니다.");
            if (!u.isHqAdmin()) scope.requireRoleOn(u, r.scopeId(), Roles.HR_MGR);
            empSite = r.scopeId();
        }
        AccountEmployee e = r.employee();
        boolean register = e != null && !Boolean.FALSE.equals(e.register()) && !Roles.INVESTOR.equals(r.role());
        if (register && (e.baseWage() == null && blank(e.salaryGrade()) == null)) throw ApiException.bad("직원 등록에는 연봉 등급 또는 급여가 필요합니다.");
        String scopeType = "HQ".equals(site) ? Objects.requireNonNullElse(r.scopeType(), "ALL") : "STORE";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginId", r.loginId()); body.put("name", r.name()); body.put("email", r.email()); body.put("phone", r.phone());
        body.put("password", blank(r.password()));
        Map<String, Object> asg = new LinkedHashMap<>();
        asg.put("role", r.role()); asg.put("scopeType", scopeType); asg.put("scopeId", "ALL".equals(scopeType) ? null : r.scopeId());
        body.put("assignments", List.of(asg));
        Map<String, Object> created = auth.createUser(http.getHeader("Authorization"), body);
        UUID userId = UUID.fromString(created.get("id").toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId); out.put("initialPassword", created.get("initialPassword"));
        if (register) {
            String title = Map.of(Roles.HQ_ADMIN, "총괄 관리자", Roles.LOGISTICS_MGR, "물류 관리자", Roles.HR_MGR, "인사 관리자", Roles.SUPERVISOR, "슈퍼바이저",
                    Roles.OWNER, "점주", Roles.STORE_MGR, "BAKERY".equals(site) ? "베이커리 관리자" : "매장 관리자", Roles.STAFF, "사원").get(r.role());
            var emp = insertEmployee(new EmployeeReq(empSite, userId, r.name(), e.hireDate(), e.employmentType(), e.payType(), e.baseWage(),
                    e.weeklyHours(), null, null, null, null, null, null, null, null, null, e.salaryGrade(), null,
                    blank(e.jobTitle()) != null ? e.jobTitle() : title));
            out.putAll(Map.of("employeeId", emp.get("id"), "empNo", emp.get("empNo")));
        }
        return out;
    }
}
