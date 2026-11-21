package kr.espresso.core.hr;

import kr.espresso.common.*;
import kr.espresso.core.support.StoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * 근무표 (매장별 계획): 주간·4주 보기, 자동 로테이션, 휴무·연차, 대체 근무, 수동 조정(승인 메모 필수), 매장 기준(오픈·마감·최번시)
 * 편집: 인사 관리자·총괄·슈퍼바이저(권한 확장)·점주·매장 관리자 / 조회: + 일반 사원 (본인 휴무·연차 신청 가능)
 */
@RestController
@RequestMapping("/api/core/hr/schedule")
public class ScheduleController {
    static final String[] EDIT = {Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR};
    static final String[] VIEW = {Roles.HR_MGR, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR, Roles.STAFF};
    static final Map<String, String> TYPE = Map.of("OPEN", "오픈", "CLOSE", "마감", "MID", "일반", "HQ", "본사", "CUSTOM", "수동");
    private final JdbcClient db;
    private final StoreScope scope;
    private final ScheduleService svc;

    public ScheduleController(JdbcClient db, StoreScope scope, ScheduleService svc) { this.db = db; this.scope = scope; this.svc = svc; }

    static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static String req(String s, String what) { if (blank(s) == null) throw ApiException.bad(what + "을(를) 입력하세요."); return s.trim(); }

    UUID storeOf(UUID emp) {
        return db.sql("select store_id from hr.employee where id = ?").param(emp).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("직원"));
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam UUID storeId, @RequestParam String from, @RequestParam String to) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, VIEW);
        LocalDate f = LocalDate.parse(from), t = LocalDate.parse(to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employees", db.sql("""
                select e.id, e.emp_no, e.name, e.job_title, e.user_id, e.status, e.resign_date, coalesce(c.weekly_contract_hours, 40) weekly_hours, c.employment_type
                from hr.employee e left join lateral (select * from hr.employment_contract c where c.employee_id = e.id and c.valid_to is null order by valid_from desc limit 1) c on true
                where e.store_id = :s and (e.status = 'ACTIVE' or exists (select 1 from hr.work_schedule w where w.employee_id = e.id and w.work_date between :f and :t))
                order by e.status, e.emp_no""").param("s", storeId).param("f", f).param("t", t).query().listOfRows());
        m.put("items", db.sql("""
                select w.id, w.employee_id, w.work_date, to_char(w.start_time, 'HH24:MI') start_time, to_char(w.end_time, 'HH24:MI') end_time, w.shift_type,
                  w.auto, w.adjust_memo, w.adjusted_at, w.substitute_for, se.name substitute_for_name, w.memo,
                  (e.status = 'RESIGNED' or (e.resign_date is not null and w.work_date > e.resign_date)) needs_adjust
                from hr.work_schedule w join hr.employee e on e.id = w.employee_id left join hr.employee se on se.id = w.substitute_for
                where w.store_id = ? and w.work_date between ? and ? order by w.work_date, w.start_time""").params(storeId, f, t).query().listOfRows());
        m.put("dayoffs", db.sql("select id, employee_id, off_date, kind, memo from hr.schedule_dayoff where store_id = ? and off_date between ? and ?")
                .params(storeId, f, t).query().listOfRows());
        m.put("logs", db.sql("""
                select l.work_date, l.action, l.before_text, l.after_text, l.memo, l.occurred_at, e.name employee_name
                from hr.schedule_log l left join hr.employee e on e.id = l.employee_id where l.store_id = ? order by l.occurred_at desc limit 40""")
                .param(storeId).query().listOfRows());
        var st = svc.setting(storeId);
        m.put("settings", Map.of("openTime", st.open().toString(), "closeTime", st.close().toString(), "peakStart", st.peakStart().toString(),
                "peakEnd", st.peakEnd().toString(), "shiftHours", st.shiftHours(), "minOpen", st.minOpen(), "minClose", st.minClose(), "minPeak", st.minPeak()));
        m.put("siteType", db.sql("select site_type from store.store where id = ?").param(storeId).query(String.class).single());
        m.put("canEdit", scope.hasRoleOn(u, storeId, EDIT));
        m.put("myEmployeeId", db.sql("select id from hr.employee where user_id = ? and store_id = ? and status = 'ACTIVE' limit 1").params(u.id(), storeId).query(UUID.class).optional().orElse(null));
        return m;
    }

    public record SettingReq(UUID storeId, String openTime, String closeTime, String peakStart, String peakEnd, BigDecimal shiftHours, Integer minOpen, Integer minClose, Integer minPeak) {}

    /** 매장 근무 기준: 오픈·마감 시각, 최번시, 1회 근무 시간, 최소 인원 */
    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestBody SettingReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), EDIT);
        LocalTime o = LocalTime.parse(req(r.openTime(), "오픈 시각")), c = LocalTime.parse(req(r.closeTime(), "마감 시각")),
                ps = LocalTime.parse(req(r.peakStart(), "최번시 시작")), pe = LocalTime.parse(req(r.peakEnd(), "최번시 끝"));
        if (!c.isAfter(o) || !pe.isAfter(ps) || ps.isBefore(o) || pe.isAfter(c)) throw ApiException.bad("최번시는 오픈~마감 사이여야 합니다.");
        double h = r.shiftHours() == null ? 8 : r.shiftHours().doubleValue();
        if (h < 3 || h > 12) throw ApiException.bad("1회 근무 시간은 3~12시간입니다.");
        db.sql("""
                insert into hr.schedule_setting(store_id, open_time, close_time, peak_start, peak_end, shift_hours, min_open, min_close, min_peak) values (?,?,?,?,?,?,?,?,?)
                on conflict (store_id) do update set open_time = excluded.open_time, close_time = excluded.close_time, peak_start = excluded.peak_start, peak_end = excluded.peak_end,
                  shift_hours = excluded.shift_hours, min_open = excluded.min_open, min_close = excluded.min_close, min_peak = excluded.min_peak, updated_at = now()""")
                .params(r.storeId(), o, c, ps, pe, h, Objects.requireNonNullElse(r.minOpen(), 1), Objects.requireNonNullElse(r.minClose(), 1), Objects.requireNonNullElse(r.minPeak(), 2)).update();
        return Map.of("ok", true);
    }

    public record GenerateReq(UUID storeId, String monday, Integer weeks, String from, String memo) {}

    /** 자동 로테이션 생성 (휴무 지정 먼저 → 나머지 자동). from 을 주면 그 날부터 다시 배치 (퇴사·변경 조정) */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody GenerateReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), EDIT);
        int w = Objects.requireNonNullElse(r.weeks(), 4);
        if (w < 1 || w > 6) throw ApiException.bad("1~6주까지 만들 수 있습니다.");
        return svc.generate(r.storeId(), LocalDate.parse(req(r.monday(), "시작 주")), w, blank(r.from()) == null ? null : LocalDate.parse(r.from()), u.id(), blank(r.memo()));
    }

    public record SlotReq(UUID employeeId, String workDate, String startTime, String endTime, String shiftType, String memo) {}

    /** 수동 조정 (승인 메모 필수) — 다시 자동 생성해도 유지 */
    @PutMapping
    @Transactional
    public Map<String, Object> put(@RequestBody SlotReq r) {
        CurrentUser u = CurrentUser.get();
        if (r.employeeId() == null) throw ApiException.bad("직원을 고르세요.");
        UUID store = storeOf(r.employeeId());
        scope.requireRoleOn(u, store, EDIT);
        String memo = req(r.memo(), "조정 사유(승인 메모)");
        LocalDate d = LocalDate.parse(req(r.workDate(), "근무일"));
        LocalTime a = LocalTime.parse(req(r.startTime(), "시작 시각")), b = LocalTime.parse(req(r.endTime(), "종료 시각"));
        if (a.equals(b)) throw ApiException.bad("시작·종료 시각이 같습니다.");
        String type = Objects.requireNonNullElse(blank(r.shiftType()), "CUSTOM");
        if (!TYPE.containsKey(type)) throw ApiException.bad("근무 구분은 오픈·마감·일반·본사·수동 중 하나입니다.");
        var before = db.sql("select shift_type, to_char(start_time,'HH24:MI') s, to_char(end_time,'HH24:MI') e from hr.work_schedule where employee_id = ? and work_date = ?")
                .params(r.employeeId(), d).query().listOfRows();
        db.sql("""
                insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, shift_type, auto, adjust_memo, adjusted_by, adjusted_at, created_by)
                values (?,?,?,?,?,?, false, ?, ?, now(), ?)
                on conflict (employee_id, work_date) do update set start_time = excluded.start_time, end_time = excluded.end_time, shift_type = excluded.shift_type,
                  adjust_memo = excluded.adjust_memo, adjusted_by = excluded.adjusted_by, adjusted_at = now()""")
                .params(store, r.employeeId(), d, a, b, type, memo, u.id(), u.id()).update();
        svc.log(store, r.employeeId(), d, "ADJUST", before.isEmpty() ? null : TYPE.get(before.get(0).get("shift_type")) + " " + before.get(0).get("s") + "–" + before.get(0).get("e"),
                TYPE.get(type) + " " + a + "–" + b, memo, u.id());
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable UUID id, @RequestParam(required = false) String memo) {
        CurrentUser u = CurrentUser.get();
        var w = db.sql("select store_id, employee_id, work_date, shift_type, to_char(start_time,'HH24:MI') s, to_char(end_time,'HH24:MI') e from hr.work_schedule where id = ?")
                .param(id).query().listOfRows();
        if (w.isEmpty()) throw ApiException.notFound("근무표");
        var x = w.get(0);
        scope.requireRoleOn(u, (UUID) x.get("store_id"), EDIT);
        String m = req(memo, "삭제 사유(승인 메모)");
        db.sql("delete from hr.work_schedule where id = ?").param(id).update();
        svc.log((UUID) x.get("store_id"), (UUID) x.get("employee_id"), ((java.sql.Date) x.get("work_date")).toLocalDate(), "DELETE",
                TYPE.get(x.get("shift_type")) + " " + x.get("s") + "–" + x.get("e"), null, m, u.id());
        return Map.of("ok", true);
    }

    public record DayoffReq(UUID employeeId, String date, String kind, String memo) {}

    /** 휴무 지정(OFF)·연차(LEAVE): 직원 본인 또는 편집 권한자. 그날 근무가 있으면 빠짐 → 대체 근무로 채움 */
    @PutMapping("/dayoff")
    @Transactional
    public Map<String, Object> dayoff(@RequestBody DayoffReq r) {
        CurrentUser u = CurrentUser.get();
        if (r.employeeId() == null) throw ApiException.bad("직원을 고르세요.");
        UUID store = storeOf(r.employeeId());
        boolean self = db.sql("select count(*) from hr.employee where id = ? and user_id = ?").params(r.employeeId(), u.id()).query(Integer.class).single() > 0;
        if (!self) scope.requireRoleOn(u, store, EDIT);
        String kind = Objects.requireNonNullElse(blank(r.kind()), "OFF");
        if (!Set.of("OFF", "LEAVE").contains(kind)) throw ApiException.bad("휴무(OFF) 또는 연차(LEAVE) 입니다.");
        LocalDate d = LocalDate.parse(req(r.date(), "날짜"));
        db.sql("""
                insert into hr.schedule_dayoff(store_id, employee_id, off_date, kind, memo, created_by) values (?,?,?,?,?,?)
                on conflict (employee_id, off_date) do update set kind = excluded.kind, memo = excluded.memo""")
                .params(store, r.employeeId(), d, kind, blank(r.memo()), u.id()).update();
        int removed = db.sql("delete from hr.work_schedule where employee_id = ? and work_date = ?").params(r.employeeId(), d).update();
        svc.log(store, r.employeeId(), d, "LEAVE".equals(kind) ? "LEAVE" : "DAYOFF", removed > 0 ? "근무 있음 → 빠짐" : null, "LEAVE".equals(kind) ? "연차" : "휴무", blank(r.memo()), u.id());
        return Map.of("ok", true, "removedShift", removed > 0);
    }

    @DeleteMapping("/dayoff/{id}")
    public Map<String, Object> deleteDayoff(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var o = db.sql("select o.store_id, o.employee_id, e.user_id from hr.schedule_dayoff o join hr.employee e on e.id = o.employee_id where o.id = ?").param(id).query().listOfRows();
        if (o.isEmpty()) throw ApiException.notFound("휴무");
        if (!u.id().equals(o.get(0).get("user_id"))) scope.requireRoleOn(u, (UUID) o.get(0).get("store_id"), EDIT);
        db.sql("delete from hr.schedule_dayoff where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    @GetMapping("/substitutes")
    public List<Map<String, Object>> substitutes(@RequestParam UUID employeeId, @RequestParam String date) {
        CurrentUser u = CurrentUser.get();
        UUID store = storeOf(employeeId);
        scope.requireRoleOn(u, store, EDIT);
        LocalDate d = LocalDate.parse(date);
        double h = db.sql("""
                select coalesce(extract(epoch from (case when end_time > start_time then end_time - start_time else end_time - start_time + interval '24 hours' end)) / 3600, 8)
                from hr.work_schedule where employee_id = ? and work_date = ?""").params(employeeId, d).query(Double.class).optional().orElse(svc.setting(store).shiftHours());
        return svc.substitutes(store, d, employeeId, h);
    }

    public record LeaveReq(UUID employeeId, String date, UUID substituteId, String memo) {}

    /** 긴급 연차: 원래 근무를 빼고 대체 인원에게 같은 시간 근무를 넣음 (메모 필수) */
    @PostMapping("/leave")
    @Transactional
    public Map<String, Object> leave(@RequestBody LeaveReq r) {
        CurrentUser u = CurrentUser.get();
        if (r.employeeId() == null) throw ApiException.bad("연차를 쓰는 직원을 고르세요.");
        UUID store = storeOf(r.employeeId());
        scope.requireRoleOn(u, store, EDIT);
        String memo = req(r.memo(), "연차·대체 사유(승인 메모)");
        LocalDate d = LocalDate.parse(req(r.date(), "날짜"));
        var w = db.sql("select start_time, end_time, shift_type from hr.work_schedule where employee_id = ? and work_date = ?").params(r.employeeId(), d).query().listOfRows();
        db.sql("""
                insert into hr.schedule_dayoff(store_id, employee_id, off_date, kind, memo, created_by) values (?,?,?, 'LEAVE', ?, ?)
                on conflict (employee_id, off_date) do update set kind = 'LEAVE', memo = excluded.memo""").params(store, r.employeeId(), d, memo, u.id()).update();
        db.sql("delete from hr.work_schedule where employee_id = ? and work_date = ?").params(r.employeeId(), d).update();
        svc.log(store, r.employeeId(), d, "LEAVE", w.isEmpty() ? null : "근무 " + w.get(0).get("start_time").toString().substring(0, 5) + "–" + w.get(0).get("end_time").toString().substring(0, 5), "연차", memo, u.id());
        if (r.substituteId() != null && !w.isEmpty()) {
            if (!store.equals(storeOf(r.substituteId()))) throw ApiException.bad("대체 인원은 같은 매장 직원이어야 합니다.");
            db.sql("""
                    insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, shift_type, auto, adjust_memo, adjusted_by, adjusted_at, substitute_for, created_by)
                    values (?,?,?,?,?,?, false, ?, ?, now(), ?, ?)
                    on conflict (employee_id, work_date) do update set start_time = excluded.start_time, end_time = excluded.end_time, shift_type = excluded.shift_type,
                      adjust_memo = excluded.adjust_memo, adjusted_by = excluded.adjusted_by, adjusted_at = now(), substitute_for = excluded.substitute_for""")
                    .params(store, r.substituteId(), d, w.get(0).get("start_time"), w.get(0).get("end_time"), w.get(0).get("shift_type"), memo, u.id(), r.employeeId(), u.id()).update();
            svc.log(store, r.substituteId(), d, "SUBSTITUTE", null, "대체 근무 " + w.get(0).get("start_time").toString().substring(0, 5) + "–" + w.get(0).get("end_time").toString().substring(0, 5), memo, u.id());
        }
        return Map.of("ok", true);
    }

    public record CopyWeekReq(UUID storeId, String fromMonday, String toMonday) {}

    /** 지난주 근무표를 이번 주로 복사 (이미 있는 날·휴무는 건너뜀) */
    @PostMapping("/copy-week")
    public Map<String, Object> copyWeek(@RequestBody CopyWeekReq r) {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, r.storeId(), EDIT);
        LocalDate f = LocalDate.parse(r.fromMonday()), t = LocalDate.parse(r.toMonday());
        int n = db.sql("""
                insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, shift_type, auto, memo, created_by)
                select s.store_id, s.employee_id, s.work_date + ?, s.start_time, s.end_time, s.shift_type, true, s.memo, ? from hr.work_schedule s
                join hr.employee e on e.id = s.employee_id and e.status = 'ACTIVE'
                where s.store_id = ? and s.work_date between ? and ?
                  and not exists (select 1 from hr.schedule_dayoff o where o.employee_id = s.employee_id and o.off_date = s.work_date + ?)
                on conflict (employee_id, work_date) do nothing""")
                .params((int) (t.toEpochDay() - f.toEpochDay()), u.id(), r.storeId(), f, f.plusDays(6), (int) (t.toEpochDay() - f.toEpochDay())).update();
        svc.log(r.storeId(), null, t, "COPY", null, n + "칸 복사", "지난주 복사", u.id());
        return Map.of("copied", n);
    }
}
