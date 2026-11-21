package kr.espresso.core.hr;

import kr.espresso.common.DemoIds;
import kr.espresso.common.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** 데모 직원(가명) + 지난달~어제 근무기록. 계정 연결: mgr1·staff1(강남), mgr2·staff2(홍대), mgr3(판교) */
@Component
@Order(6)
public class HrDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(HrDemoSeeder.class);
    private final JdbcClient db;
    private final boolean enabled;

    private final ScheduleService schedules;

    public HrDemoSeeder(JdbcClient db, ScheduleService schedules, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.schedules = schedules; this.enabled = enabled; }

    record Emp(UUID store, UUID user, String name, String type, String pay, long wage, int weekly, int[] days, String in, String out, int brk) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (db.sql("select count(*) from hr.employee").query(Integer.class).single() > 0) { seedR1(); seedR2(); return; }
        List<Emp> emps = List.of(
                new Emp(DemoIds.STORE_GANGNAM, DemoIds.MGR1, "김매니저", "REGULAR", "MONTHLY", 2_700_000, 40, new int[]{1, 2, 3, 4, 5}, "09:00", "18:00", 60),
                new Emp(DemoIds.STORE_GANGNAM, DemoIds.STAFF1, "강사원", "PART_TIME", "HOURLY", 10_500, 20, new int[]{1, 3, 5, 6}, "17:00", "22:30", 30),
                new Emp(DemoIds.STORE_GANGNAM, null, "알바A", "PART_TIME", "HOURLY", 10_320, 14, new int[]{6, 7}, "10:00", "17:30", 30),
                new Emp(DemoIds.STORE_GANGNAM, null, "바리스타B", "FREELANCER", "HOURLY", 15_000, 12, new int[]{7}, "09:00", "21:00", 60),
                new Emp(DemoIds.STORE_HONGDAE, DemoIds.MGR2, "오매니저", "REGULAR", "MONTHLY", 2_600_000, 40, new int[]{2, 3, 4, 5, 6}, "10:00", "19:00", 60),
                new Emp(DemoIds.STORE_HONGDAE, DemoIds.STAFF2, "임사원", "PART_TIME", "HOURLY", 10_320, 24, new int[]{4, 5, 6, 7}, "14:00", "21:00", 60),
                new Emp(DemoIds.STORE_PANGYO, DemoIds.MGR3, "서매니저", "REGULAR", "MONTHLY", 2_800_000, 40, new int[]{1, 2, 3, 4, 5}, "08:00", "17:00", 60),
                new Emp(DemoIds.STORE_PANGYO, null, "알바C", "PART_TIME", "HOURLY", 10_400, 25, new int[]{1, 2, 3, 4, 5}, "12:00", "17:30", 30));
        LocalDate from = LocalDate.now().minusMonths(1).withDayOfMonth(1), to = LocalDate.now().minusDays(1);
        int i = 1, recs = 0;
        for (Emp e : emps) {
            UUID id = UUID.randomUUID();
            db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, hire_date) values (?,?,?, lpad(nextval('hr.emp_no_seq')::text, 8, '0'), ?, date '2026-01-15')")
                    .params(id, e.user(), e.store(), e.name()).update();
            i++;
            boolean free = e.type().equals("FREELANCER");
            db.sql("""
                    insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, weekly_holiday_pay, income_tax_type,
                      national_pension, health_insurance, employment_insurance, non_taxable_allowances, valid_from)
                    values (?,?,?,?,?,?,?,?,?,?, cast(? as jsonb), date '2026-01-15')""")
                    .params(id, e.type(), e.pay(), e.wage(), e.weekly(), !free && e.weekly() >= 15, free ? "BIZ_3_3" : "WAGE",
                            !free && e.weekly() >= 15, !free && e.weekly() >= 15, !free, e.type().equals("REGULAR") ? "{\"meal\":200000}" : "{}").update();
            Set<Integer> days = new HashSet<>(); for (int d : e.days()) days.add(d);
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!days.contains(d.getDayOfWeek().getValue())) continue;
                OffsetDateTime in = d.atTime(LocalTime.parse(e.in())).atZone(WorkCalc.KST).toOffsetDateTime();
                OffsetDateTime out = d.atTime(LocalTime.parse(e.out())).atZone(WorkCalc.KST).toOffsetDateTime();
                var m = WorkCalc.split(in, out, e.brk());
                db.sql("""
                        insert into hr.work_record(employee_id, store_id, work_date, clock_in, clock_out, break_minutes, regular_minutes, overtime_minutes, night_minutes, source, confirmed_at)
                        values (?,?,?,?,?,?,?,?,?, 'MANUAL', now())""").params(id, e.store(), d, in, out, e.brk(), m.regular(), m.overtime(), m.night()).update();
                recs++;
            }
        }
        log.info("demo employees seeded: {} employees, {} work records", emps.size(), recs);
        seedR1();
        seedR2();
    }

    /** 1차 점검 반영 데모: 본사 직원(총괄·물류·인사)과 베이커리 관리자를 연봉 등급으로 등록 (계정 연결 → 첫 화면 출퇴근) */
    private void seedR1() {
        db.sql("update hr.employee set name = '장총괄' where user_id = ? and name = '최총괄'").param(DemoIds.ADMIN).update();
        if (db.sql("select count(*) from hr.employee where store_id = ?").param(Sites.HQ).query(Integer.class).single() > 0) return;
        Object[][] list = {
                {Sites.HQ, DemoIds.ADMIN, "장총괄", "총괄 관리자", "a5"},
                {Sites.HQ, DemoIds.LOGI, "박물류", "물류 관리자", "l3"},
                {Sites.HQ, DemoIds.HR, "정인사", "인사 관리자", "h3"},
                {DemoIds.BAKERY_CENTRAL, DemoIds.BAKER1, "문베이커", "베이커리 관리자", "m2"}};
        for (Object[] e : list) {
            UUID id = UUID.randomUUID();
            db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, job_title, hire_date) values (?,?,?, lpad(nextval('hr.emp_no_seq')::text, 8, '0'), ?, ?, date '2026-01-02')")
                    .params(id, e[1], e[0], e[2], e[3]).update();
            db.sql("""
                    insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, salary_grade, non_taxable_allowances, valid_from)
                    select ?, 'REGULAR', 'MONTHLY', annual_salary / 12, 40, code, '{"meal":200000}'::jsonb, date '2026-01-02' from hr.salary_grade where code = ?""")
                    .params(id, e[4]).update();
        }
        log.info("demo HQ/bakery employees seeded (salary grades)");
    }

    /**
     * 2차 점검 반영 데모 (가상): 슈퍼바이저 본사 직원, 매장별 아르바이트 보강(로테이션용), 매장 근무 기준,
     * 휴무 지정 예시 후 이번 주부터 4주 자동 근무표
     */
    private void seedR2() {
        if (db.sql("select count(*) from hr.schedule_setting").query(Integer.class).single() > 0) return;
        if (db.sql("select count(*) from hr.employee where user_id = ?").param(DemoIds.SV).query(Integer.class).single() == 0)
            emp(Sites.HQ, DemoIds.SV, "윤감독", "슈퍼바이저", "REGULAR", "MONTHLY", 0, 40, "v2");
        Object[][] extra = {
                {DemoIds.STORE_GANGNAM, "알바D", 10_400, 30}, {DemoIds.STORE_GANGNAM, "알바E", 10_320, 24},
                {DemoIds.STORE_HONGDAE, "알바F", 10_400, 30}, {DemoIds.STORE_HONGDAE, "알바G", 10_320, 24}, {DemoIds.STORE_HONGDAE, "알바H", 10_320, 20},
                {DemoIds.STORE_PANGYO, "알바I", 10_400, 30}, {DemoIds.STORE_PANGYO, "알바J", 10_320, 24},
                {DemoIds.BAKERY_CENTRAL, "베이커K", 11_000, 40}, {DemoIds.BAKERY_CENTRAL, "베이커L", 10_800, 30}};
        for (Object[] e : extra)
            if (db.sql("select count(*) from hr.employee where store_id = ? and name = ?").params(e[0], e[1]).query(Integer.class).single() == 0)
                emp((UUID) e[0], null, (String) e[1], "아르바이트", "PART_TIME", "HOURLY", ((Number) e[2]).longValue(), ((Number) e[3]).intValue(), null);
        LocalDate monday = LocalDate.now(WorkCalc.KST).with(DayOfWeek.MONDAY);
        for (UUID st : db.sql("select id from store.store where status = 'OPEN' and site_type in ('STORE','BAKERY','HQ')").query(UUID.class).list()) {
            boolean bakery = DemoIds.BAKERY_CENTRAL.equals(st);
            db.sql("insert into hr.schedule_setting(store_id, open_time, close_time, peak_start, peak_end, shift_hours, min_open, min_close, min_peak) values (?,?,?,?,?, 8, 1, 1, 2) on conflict do nothing")
                    .params(st, bakery ? LocalTime.of(5, 0) : LocalTime.of(7, 0), bakery ? LocalTime.of(18, 0) : LocalTime.of(22, 0),
                            bakery ? LocalTime.of(6, 0) : LocalTime.of(11, 30), bakery ? LocalTime.of(9, 0) : LocalTime.of(14, 0)).update();
            // 휴무 지정 예시: 각 매장 첫 직원 다음 주 수요일 휴무
            db.sql("""
                    insert into hr.schedule_dayoff(store_id, employee_id, off_date, kind, memo)
                    select store_id, id, ?, 'OFF', '개인 일정 (데모)' from hr.employee where store_id = ? and status = 'ACTIVE' order by emp_no limit 1 on conflict do nothing""")
                    .params(monday.plusDays(9), st).update();
            schedules.generate(st, monday, 4, null, DemoIds.ADMIN, "데모 자동 로테이션");
        }
        log.info("demo schedules seeded (4 weeks from {})", monday);
    }

    private void emp(UUID store, UUID user, String name, String title, String type, String pay, long wage, int weekly, String grade) {
        UUID id = UUID.randomUUID();
        db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, job_title, hire_date) values (?,?,?, lpad(nextval('hr.emp_no_seq')::text, 8, '0'), ?, ?, date '2026-02-01')")
                .params(id, user, store, name, title).update();
        if (grade != null)
            db.sql("""
                    insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, salary_grade, non_taxable_allowances, valid_from)
                    select ?, 'REGULAR', 'MONTHLY', annual_salary / 12, ?, code, '{"meal":200000}'::jsonb, date '2026-02-01' from hr.salary_grade where code = ?""")
                    .params(id, weekly, grade).update();
        else
            db.sql("""
                    insert into hr.employment_contract(employee_id, employment_type, pay_type, base_wage, weekly_contract_hours, weekly_holiday_pay, national_pension, health_insurance, employment_insurance, valid_from)
                    values (?,?,?,?,?, ?, ?, ?, true, date '2026-02-01')""")
                    .params(id, type, pay, wage, weekly, weekly >= 15, weekly >= 15, weekly >= 15).update();
    }
}
