package kr.espresso.core.hr;

import kr.espresso.common.DemoIds;
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

    public HrDemoSeeder(JdbcClient db, @Value("${app.demo.seed}") boolean enabled) { this.db = db; this.enabled = enabled; }

    record Emp(UUID store, UUID user, String name, String type, String pay, long wage, int weekly, int[] days, String in, String out, int brk) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || db.sql("select count(*) from hr.employee").query(Integer.class).single() > 0) return;
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
            db.sql("insert into hr.employee(id, user_id, store_id, emp_no, name, hire_date) values (?,?,?,?,?, date '2026-01-15')")
                    .params(id, e.user(), e.store(), String.format("E%05d", i++), e.name()).update();
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
    }
}
