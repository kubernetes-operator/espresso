package kr.espresso.settlement.close;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.settlement.support.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 주 마감 (2차 점검 반영): 월요일 시작 ~ 일요일. 일요일 24시(= 월요일 00:00 KST)에 지난주를 자동 마감.
 * 월 마감과 달리 분개를 잠그지 않는 '주간 확정 실적' 스냅샷 — 마감 이후 들어온 분개는 다음 조회 시 차이로 보인다.
 * 서비스 기동 시 빠진 지난 주도 채운다.
 */
@Service
public class WeekCloseService {
    private static final Logger log = LoggerFactory.getLogger(WeekCloseService.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final List<String> SECTIONS = List.of("SALES", "COGS", "LABOR", "RENT", "UTILITY", "INTERNAL_USE", "OPERATING", "STOCK_LOSS", "DEPRECIATION", "ROYALTY", "NON_OP_INCOME", "NON_OP_EXPENSE");
    private final JdbcClient db;
    private final CoreClient core;
    private final ObjectMapper om;
    private final SettlementService stl;

    public WeekCloseService(JdbcClient db, CoreClient core, ObjectMapper om, SettlementService stl) { this.db = db; this.core = core; this.om = om; this.stl = stl; }

    /**
     * 고정비 일할 배분 (3차 점검 반영): 임대 계약의 월세+관리비(공급가)와 월 감가상각을 날짜별로 (월 금액 ÷ 그 달 일수) 나눠 그 주 일수만큼.
     * 월말에 한 번 잡히는 임대·감가상각 분개 대신 이 값을 주간 결산에 넣는다.
     */
    long[] fixedCosts(UUID store, LocalDate monday) {
        long rent = 0, dep = 0;
        List<Map<String, Object>> leases;
        try { leases = core.leases(store); } catch (Exception e) { leases = List.of(); }
        Map<String, Long> depByMonth = new HashMap<>();
        double rentD = 0, depD = 0;
        for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
            int days = d.lengthOfMonth();
            for (var l : leases) {
                LocalDate a = LocalDate.parse(l.get("start_date").toString());
                LocalDate b = l.get("end_date") == null ? LocalDate.MAX : LocalDate.parse(l.get("end_date").toString());
                if (d.isBefore(a) || d.isAfter(b)) continue;
                rentD += (((Number) l.get("monthly_rent")).longValue() + ((Number) l.get("maintenance_fee")).longValue()) / (double) days;
            }
            String p = String.format("%d%02d", d.getYear(), d.getMonthValue());
            long monthDep = depByMonth.computeIfAbsent(p, k -> stl.depreciationFor(store, k).values().stream().mapToLong(Long::longValue).sum());
            depD += monthDep / (double) days;
        }
        rent = Math.round(rentD); dep = Math.round(depD);
        return new long[]{rent, dep};
    }

    /** 주간 실적 (분개 일자 기준) */
    public Map<String, Object> compute(UUID store, LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        Map<String, Long> s = new LinkedHashMap<>();
        for (String k : SECTIONS) s.put(k, 0L);
        for (var r : db.sql("""
                select a.pnl_section sec, coalesce(sum(case when a.type = 'REVENUE' then l.credit - l.debit else l.debit - l.credit end), 0) amt
                from stl.journal_line l join stl.account_subject a on a.code = l.account_code
                where l.store_id = ? and l.entry_date between ? and ? and a.pnl_section is not null group by 1""").params(store, monday, sunday).query().listOfRows())
            s.put((String) r.get("sec"), ((Number) r.get("amt")).longValue());
        long[] fixed = fixedCosts(store, monday);
        s.put("RENT", fixed[0]); s.put("DEPRECIATION", fixed[1]);   // 고정비는 일할 배분 값으로
        long sales = s.get("SALES"), gross = sales - s.get("COGS");
        long sga = s.get("LABOR") + s.get("RENT") + s.get("UTILITY") + s.get("INTERNAL_USE") + s.get("OPERATING") + s.get("STOCK_LOSS") + s.get("DEPRECIATION");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store_id", store); m.put("week_start", monday.toString()); m.put("week_end", sunday.toString());
        m.put("net_sales", sales); m.put("cogs", s.get("COGS")); m.put("gross_profit", gross);
        m.put("labor", s.get("LABOR")); m.put("rent", s.get("RENT")); m.put("utility", s.get("UTILITY")); m.put("internal_use", s.get("INTERNAL_USE"));
        m.put("operating_exp", s.get("OPERATING")); m.put("stock_loss", s.get("STOCK_LOSS")); m.put("depreciation", s.get("DEPRECIATION"));
        m.put("operating_profit", gross - sga);
        m.put("fixed_prorated", true);
        return m;
    }

    public boolean close(UUID store, LocalDate monday, boolean auto) {
        Map<String, Object> m = compute(store, monday);
        String json;
        try { json = om.writeValueAsString(m); } catch (Exception e) { json = "{}"; }
        return db.sql("""
                insert into stl.week_close(store_id, week_start, week_end, net_sales, operating_profit, data, auto) values (?,?,?,?,?, cast(? as jsonb), ?)
                on conflict do nothing""").params(store, monday, monday.plusDays(6), m.get("net_sales"), m.get("operating_profit"), json, auto).update() > 0;
    }

    static LocalDate lastClosedMonday() { return LocalDate.now(KST).with(DayOfWeek.MONDAY).minusWeeks(1); }

    List<UUID> sites() {
        List<UUID> out = new ArrayList<>();
        for (var st : core.allStores())
            if (!"HQ".equals(st.get("site_type"))) out.add(UUID.fromString(st.get("id").toString()));
        return out;
    }

    /** 일요일 24시 = 월요일 00:00 (KST) 자동 주 마감 */
    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Seoul")
    public void weekly() {
        LocalDate mon = lastClosedMonday();
        int n = 0;
        for (UUID s : sites()) if (close(s, mon, true)) n++;
        log.info("week close {}~{}: {} sites", mon, mon.plusDays(6), n);
    }

    /** 빠진 주 채우기 (분개가 있는 첫 주부터 지난주까지) — 기동 1분 뒤부터 1시간마다, 이미 있는 주는 건너뜀 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void catchUp() {
        try {
            var first = db.sql("select min(entry_date) from stl.journal_entry").query(java.sql.Date.class).optional();
            if (first.isEmpty() || first.get() == null) return;
            LocalDate last = lastClosedMonday();
            int n = 0;
            for (UUID s : sites())
                for (LocalDate m = first.get().toLocalDate().with(DayOfWeek.MONDAY); !m.isAfter(last); m = m.plusWeeks(1))
                    if (close(s, m, true)) n++;
            if (n > 0) log.info("week close catch-up: {} weeks", n);
        } catch (Exception e) { log.warn("week close catch-up skipped: {}", e.getMessage()); }
    }
}
