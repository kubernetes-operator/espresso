package kr.espresso.core.hr;

import kr.espresso.common.ApiException;
import kr.espresso.common.Sites;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 근무표 자동 로테이션 (매장별, 월요일 시작 주 단위)
 *  1) 직원이 먼저 지정한 휴무(OFF)·연차(LEAVE) 날은 비움
 *  2) 매일 오픈 → 마감 → 최번시 보강(일반) 순서로 배정 — 인원이 모자라면 오픈·마감을 먼저 채움
 *  3) 직원별 주 소정 시간(계약)과 주 최대 일수 안에서, 오픈·마감 횟수가 고르게 돌아가도록 배정
 *  4) 수동 조정(adjusted_at 있음)한 칸은 다시 만들 때도 유지
 *  5) 본사 사이트는 로테이션 없이 평일 09:00~18:00
 */
@Service
public class ScheduleService {
    private final JdbcClient db;
    public ScheduleService(JdbcClient db) { this.db = db; }

    public record Setting(LocalTime open, LocalTime close, LocalTime peakStart, LocalTime peakEnd, double shiftHours, int minOpen, int minClose, int minPeak) {}

    public Setting setting(UUID store) {
        var r = db.sql("select * from hr.schedule_setting where store_id = ?").param(store).query().listOfRows();
        if (r.isEmpty()) return new Setting(LocalTime.of(7, 0), LocalTime.of(22, 0), LocalTime.of(11, 30), LocalTime.of(14, 0), 8, 1, 1, 2);
        var m = r.get(0);
        return new Setting(((java.sql.Time) m.get("open_time")).toLocalTime(), ((java.sql.Time) m.get("close_time")).toLocalTime(),
                ((java.sql.Time) m.get("peak_start")).toLocalTime(), ((java.sql.Time) m.get("peak_end")).toLocalTime(),
                ((BigDecimal) m.get("shift_hours")).doubleValue(), ((Number) m.get("min_open")).intValue(),
                ((Number) m.get("min_close")).intValue(), ((Number) m.get("min_peak")).intValue());
    }

    static class Emp {
        UUID id; String name; double weekly; int maxDays; LocalDate resign;
        double usedH; int usedD; int opens, closes; Set<LocalDate> busy = new HashSet<>();
    }
    record Slot(String type, LocalTime start, LocalTime end) {
        double hours() { long m = Duration.between(start, end).toMinutes(); if (m <= 0) m += 1440; return m / 60.0; }
        boolean covers(LocalTime a, LocalTime b) { return !start.isAfter(a) && !end.isBefore(b); }
    }

    static LocalTime plus(LocalTime t, double h) { return t.plusMinutes(Math.round(h * 60)); }

    /** 근무 1회 시간대: 오픈 = 오픈 시각부터, 마감 = 마감 시각까지, 일반 = 최번시 가운데 */
    static Slot slot(String type, Setting s, double h) {
        return switch (type) {
            case "OPEN" -> new Slot("OPEN", s.open(), plus(s.open(), h));
            case "CLOSE" -> new Slot("CLOSE", plus(s.close(), -h), s.close());
            default -> {
                double peakH = Duration.between(s.peakStart(), s.peakEnd()).toMinutes() / 60.0;
                LocalTime st = plus(s.peakStart(), -Math.max(0, (h - peakH) / 2));
                if (st.isBefore(s.open())) st = s.open();
                LocalTime en = plus(st, h);
                if (en.isAfter(s.close())) { en = s.close(); st = plus(en, -h); }
                yield new Slot("MID", st, en);
            }
        };
    }

    /**
     * 자동 생성: monday 부터 weeks 주. from 이 있으면 그 날부터만 다시 만든다 (퇴사 등 재배치).
     * @return created, shortages(날짜별 못 채운 칸)
     */
    @Transactional
    public Map<String, Object> generate(UUID store, LocalDate monday, int weeks, LocalDate from, UUID actor, String memo) {
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) monday = monday.with(DayOfWeek.MONDAY);
        LocalDate end = monday.plusWeeks(weeks).minusDays(1);
        LocalDate start = from == null || from.isBefore(monday) ? monday : from;
        if (start.isAfter(end)) throw ApiException.bad("다시 만들 기간이 없습니다.");
        String site = db.sql("select site_type from store.store where id = ?").param(store).query(String.class).optional().orElseThrow(() -> ApiException.notFound("매장"));
        // 자동 생성분(수동 조정 안 한 칸)과 퇴사자 이후 근무는 지우고 다시 만든다
        db.sql("delete from hr.work_schedule where store_id = ? and work_date between ? and ? and auto and adjusted_at is null").params(store, start, end).update();
        db.sql("""
                delete from hr.work_schedule w using hr.employee e where w.employee_id = e.id and w.store_id = ? and w.work_date between ? and ?
                  and (e.status = 'RESIGNED' or (e.resign_date is not null and w.work_date > e.resign_date))""").params(store, start, end).update();
        var offs = new HashMap<UUID, Set<LocalDate>>();
        for (var o : db.sql("select employee_id, off_date from hr.schedule_dayoff where store_id = ? and off_date between ? and ?").params(store, monday, end).query().listOfRows())
            offs.computeIfAbsent((UUID) o.get("employee_id"), k -> new HashSet<>()).add(((java.sql.Date) o.get("off_date")).toLocalDate());
        List<Emp> emps = new ArrayList<>();
        for (var e : db.sql("""
                select e.id, e.name, e.resign_date, coalesce(c.weekly_contract_hours, 40) wh from hr.employee e
                left join lateral (select weekly_contract_hours from hr.employment_contract c where c.employee_id = e.id and c.valid_to is null order by valid_from desc limit 1) c on true
                where e.store_id = ? and e.status = 'ACTIVE' order by e.emp_no""").param(store).query().listOfRows()) {
            Emp x = new Emp(); x.id = (UUID) e.get("id"); x.name = (String) e.get("name");
            x.weekly = ((BigDecimal) e.get("wh")).doubleValue();
            x.resign = e.get("resign_date") == null ? null : ((java.sql.Date) e.get("resign_date")).toLocalDate();
            emps.add(x);
        }
        Setting s = setting(store);
        int created = 0;
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (LocalDate wk = monday; !wk.isAfter(end); wk = wk.plusWeeks(1)) {
            LocalDate wkEnd = wk.plusDays(6);
            // 이번 주 이미 있는 근무(수동 조정분·생성 범위 밖) 반영
            for (Emp x : emps) {
                x.usedH = 0; x.usedD = 0; x.busy.clear();
                x.maxDays = "HQ".equals(site) ? 5 : (int) Math.min(6, Math.max(1, Math.ceil(x.weekly / s.shiftHours())));
            }
            for (var w : db.sql("select employee_id, work_date, start_time, end_time from hr.work_schedule where store_id = ? and work_date between ? and ?")
                    .params(store, wk, wkEnd).query().listOfRows()) {
                Emp x = emps.stream().filter(e -> e.id.equals(w.get("employee_id"))).findFirst().orElse(null);
                if (x == null) continue;
                x.busy.add(((java.sql.Date) w.get("work_date")).toLocalDate()); x.usedD++;
                x.usedH += new Slot("X", ((java.sql.Time) w.get("start_time")).toLocalTime(), ((java.sql.Time) w.get("end_time")).toLocalTime()).hours();
            }
            for (LocalDate d = wk; !d.isAfter(wkEnd); d = d.plusDays(1)) {
                if (d.isBefore(start) || d.isAfter(end)) continue;
                final LocalDate day = d;
                List<Emp> avail = emps.stream().filter(x -> !x.busy.contains(day) && !offs.getOrDefault(x.id, Set.of()).contains(day)
                        && (x.resign == null || !day.isAfter(x.resign)) && x.usedD < x.maxDays && x.weekly - x.usedH >= 3.9).toList();
                if ("HQ".equals(site)) {   // 본사: 평일 9 to 6
                    if (day.getDayOfWeek().getValue() > 5) continue;
                    for (Emp x : avail) { insert(store, x, day, new Slot("HQ", LocalTime.of(9, 0), LocalTime.of(18, 0)), actor); created++; }
                    continue;
                }
                List<Slot> todays = new ArrayList<>();
                // 주 소정 시간 중 아직 덜 쓴 비율이 큰 사람(= 사용률 낮은 사람)부터 → 시간이 적은 아르바이트도 고르게 배정
                java.util.function.ToDoubleFunction<Emp> used = x -> x.usedH / Math.max(1, x.weekly);
                for (int i = 0; i < s.minOpen(); i++) {
                    Emp x = pick(avail, day, Comparator.comparingInt((Emp e) -> e.opens).thenComparingDouble(used));
                    if (x == null) { shortages.add(Map.of("date", day.toString(), "slot", "오픈")); break; }
                    todays.add(assign(store, x, day, "OPEN", s, actor)); x.opens++; created++;
                }
                for (int i = 0; i < s.minClose(); i++) {
                    Emp x = pick(avail, day, Comparator.comparingInt((Emp e) -> e.closes).thenComparingDouble(used));
                    if (x == null) { shortages.add(Map.of("date", day.toString(), "slot", "마감")); break; }
                    todays.add(assign(store, x, day, "CLOSE", s, actor)); x.closes++; created++;
                }
                while (todays.stream().filter(t -> t.covers(s.peakStart(), s.peakEnd())).count() < s.minPeak()) {
                    Emp x = pick(avail, day, Comparator.comparingDouble(used));
                    if (x == null) { shortages.add(Map.of("date", day.toString(), "slot", "최번시")); break; }
                    todays.add(assign(store, x, day, "MID", s, actor)); created++;
                }
            }
        }
        log(store, null, start, "GENERATE", null, created + "칸 생성 (" + start + "~" + end + ")" + (shortages.isEmpty() ? "" : ", 부족 " + shortages.size() + "칸"),
                memo == null ? "자동 로테이션" : memo, actor);
        return Map.of("created", created, "shortages", shortages, "from", start.toString(), "to", end.toString());
    }

    /** 그날 아직 배정 안 됐고, 주 최대 일수·남은 소정 시간(4시간 이상)이 남은 사람 중 order 1순위 */
    Emp pick(List<Emp> avail, LocalDate day, Comparator<Emp> order) {
        return avail.stream().filter(x -> !x.busy.contains(day) && x.usedD < x.maxDays && x.weekly - x.usedH >= 3.9).sorted(order).findFirst().orElse(null);
    }

    Slot assign(UUID store, Emp x, LocalDate d, String type, Setting s, UUID actor) {
        double h = Math.min(s.shiftHours(), x.weekly - x.usedH);
        Slot sl = slot(type, s, h);
        insert(store, x, d, sl, actor);
        return sl;
    }

    void insert(UUID store, Emp x, LocalDate d, Slot sl, UUID actor) {
        db.sql("""
                insert into hr.work_schedule(store_id, employee_id, work_date, start_time, end_time, shift_type, auto, created_by)
                values (?,?,?,?,?,?, true, ?) on conflict (employee_id, work_date) do nothing""")
                .params(store, x.id, d, sl.start(), sl.end(), sl.type(), actor).update();
        x.busy.add(d); x.usedD++; x.usedH += sl.hours();
    }

    public void log(UUID store, UUID emp, LocalDate d, String action, String before, String after, String memo, UUID actor) {
        db.sql("insert into hr.schedule_log(store_id, employee_id, work_date, action, before_text, after_text, memo, actor_id) values (?,?,?,?,?,?,?,?)")
                .params(store, emp, d, action, before, after, memo, actor).update();
    }

    /** 대체 근무 후보: 그날 근무·휴무가 없고, 그 주 남은 소정 시간이 있는 재직자 (남은 시간 많은 순) */
    public List<Map<String, Object>> substitutes(UUID store, LocalDate d, UUID except, double hours) {
        LocalDate wk = d.with(DayOfWeek.MONDAY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : db.sql("""
                select e.id, e.name, e.job_title, coalesce(c.weekly_contract_hours, 40) wh,
                  coalesce((select sum(extract(epoch from (case when w.end_time > w.start_time then w.end_time - w.start_time else w.end_time - w.start_time + interval '24 hours' end)) / 3600)
                            from hr.work_schedule w where w.employee_id = e.id and w.work_date between ? and ?), 0) used
                from hr.employee e
                left join lateral (select weekly_contract_hours from hr.employment_contract c where c.employee_id = e.id and c.valid_to is null order by valid_from desc limit 1) c on true
                where e.store_id = ? and e.status = 'ACTIVE' and e.id <> ? and (e.resign_date is null or e.resign_date >= ?)
                  and not exists (select 1 from hr.work_schedule w where w.employee_id = e.id and w.work_date = ?)
                  and not exists (select 1 from hr.schedule_dayoff o where o.employee_id = e.id and o.off_date = ?)""")
                .params(wk, wk.plusDays(6), store, except, d, d, d).query().listOfRows()) {
            double left = ((BigDecimal) e.get("wh")).doubleValue() - ((Number) e.get("used")).doubleValue();
            Map<String, Object> m = new LinkedHashMap<>(e);
            m.put("remaining_hours", Math.round(left * 10) / 10.0);
            m.put("enough", left >= hours);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("enough")).thenComparing(m -> -((Double) m.get("remaining_hours"))));
        return out;
    }

    static long days(LocalDate a, LocalDate b) { return ChronoUnit.DAYS.between(a, b); }
    static boolean isHq(UUID store) { return Sites.HQ.equals(store); }
}
