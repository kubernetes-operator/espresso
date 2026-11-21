package kr.espresso.settlement.close;

import kr.espresso.common.ApiException;
import kr.espresso.settlement.ledger.Ledger;
import kr.espresso.settlement.ledger.Ledger.Line;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * 마감 후 처리 (수정 결산): 월 마감 뒤에 생긴 처리 건을 마감월 결산에 반영한다.
 *  - 마감 후 발생 건: 마감월 일자로 들어왔지만 마감 때문에 현재 기간으로 넘어간 분개 → [이 달에 반영] 하면
 *    현재 기간에서 역분개하고 원래 일자(마감월)로 다시 기록
 *  - 직접 조정: 계정·금액·상대 계정·사유로 마감월에 조정 분개
 *  - 반영할 때마다 결산을 다시 계산해 새 버전(수정 결산 vN)으로 저장, 로열티가 달라지면 차액을 분개하고
 *    본사 청구(다음 정산서)에 차액 항목을 추가. 마감 상태는 그대로 유지.
 */
@Service
public class PostCloseService {
    static final Set<String> COUNTERS = Set.of("103", "101", "253", "251", "146", "108");
    private final JdbcClient db;
    private final Ledger ledger;
    private final SettlementService stl;

    public PostCloseService(JdbcClient db, Ledger ledger, SettlementService stl) { this.db = db; this.ledger = ledger; this.stl = stl; }

    void requireClosed(UUID store, String period) {
        if (!ledger.isClosed(store, period)) throw ApiException.conflict("마감된 달에서만 마감 후 처리를 할 수 있습니다. 마감 전 달은 평소처럼 입력하면 됩니다.");
    }

    static String memo(String m) {
        if (m == null || m.isBlank()) throw ApiException.bad("처리 사유(메모)를 입력하세요.");
        return m.trim();
    }

    public Map<String, Object> view(UUID store, String period) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("closed", ledger.isClosed(store, period));
        out.put("late", db.sql("""
                select e.id, e.entry_date, e.period current_period, e.orig_date, e.source_type, e.description, e.created_at,
                       sum(l.debit) amount,
                       sum(case when a.pnl_section is null then 0 when a.type = 'REVENUE' then l.credit - l.debit else l.debit - l.credit end) pnl_amount,
                       string_agg(distinct a.name, ', ') filter (where a.pnl_section is not null) pnl_accounts
                from stl.journal_entry e join stl.journal_line l on l.entry_id = e.id and l.entry_date = e.entry_date
                join stl.account_subject a on a.code = l.account_code
                where e.store_id = ? and e.orig_period = ? and e.source_type not like '%\\_VOID' and e.source_type not like '%\\_REVERSAL'
                group by e.id, e.entry_date, e.period, e.orig_date, e.source_type, e.description, e.created_at order by e.orig_date, e.created_at""")
                .params(store, period).query().listOfRows());
        out.put("adjustments", db.sql("""
                select c.*, a.name account_name, b.name counter_name from stl.close_adjustment c
                left join stl.account_subject a on a.code = c.account_code left join stl.account_subject b on b.code = c.counter_code
                where c.store_id = ? and c.period = ? order by c.created_at desc""").params(store, period).query().listOfRows());
        out.put("versions", db.sql("""
                select version, calculated_at, revised_by, revise_memo, net_sales, operating_profit, royalty, non_operating, settlement_amount
                from stl.settlement_summary where store_id = ? and period = ? order by version desc""").params(store, period).query().listOfRows());
        return out;
    }

    /** 마감 후 발생 건을 마감월로 옮김: 현재 기간 역분개 + 원래 일자로 재기록 → 수정 결산 */
    @Transactional
    public Map<String, Object> pullBack(UUID store, String period, UUID entryId, String memo, UUID actor) {
        memo = memo(memo);
        requireClosed(store, period);
        var rows = db.sql("select * from stl.journal_entry where id = ? and store_id = ?").params(entryId, store).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("분개");
        var e = rows.get(0);
        String type = (String) e.get("source_type");
        if (!period.equals(e.get("orig_period")) || type.endsWith("_VOID") || type.endsWith("_REVERSAL"))
            throw ApiException.conflict("이 달의 마감 후 발생 건이 아니거나 이미 반영했습니다.");
        LocalDate at = ((java.sql.Date) e.get("entry_date")).toLocalDate();
        LocalDate orig = e.get("orig_date") != null ? ((java.sql.Date) e.get("orig_date")).toLocalDate() : SettlementService.ym(period).atEndOfMonth();
        UUID src = (UUID) e.get("source_id");
        String desc = Objects.toString(e.get("description"), "");
        var lines = db.sql("select account_code, debit, credit, division_code, memo from stl.journal_line where entry_id = ?").param(entryId).query().listOfRows();
        List<Line> rev = new ArrayList<>(), same = new ArrayList<>();
        for (var l : lines) {
            long dr = ((Number) l.get("debit")).longValue(), cr = ((Number) l.get("credit")).longValue();
            rev.add(new Line((String) l.get("account_code"), cr, dr, (String) l.get("division_code"), "마감월 반영 역분개"));
            same.add(new Line((String) l.get("account_code"), dr, cr, (String) l.get("division_code"), (String) l.get("memo")));
        }
        ledger.postInto(store, at, type + "_REVERSAL", src, "마감월 " + period + " 반영으로 이동: " + desc, rev, actor);
        db.sql("update stl.journal_entry set source_type = source_type || '_VOID' where id = ?").param(entryId).update();
        UUID moved = ledger.postInto(store, orig, type, src, "[마감 후 반영] " + desc, same, actor);
        // 손익 영향액 (매출 +, 비용 +). 손익 계정이 없으면(재고 입고 등) 분개 금액
        long amount = 0, total = 0;
        for (var l : db.sql("select a.type, a.pnl_section, l.debit, l.credit from stl.journal_line l join stl.account_subject a on a.code = l.account_code where l.entry_id = ?").param(moved).query().listOfRows()) {
            long dr = ((Number) l.get("debit")).longValue(), cr = ((Number) l.get("credit")).longValue();
            total += dr;
            if (l.get("pnl_section") != null) amount += "REVENUE".equals(l.get("type")) ? cr - dr : dr - cr;
        }
        if (amount == 0) amount = total;
        refreshWeeks(store, at, orig);
        return revise(store, period, "LATE", null, null, amount, orig, desc, memo, moved, actor);
    }

    public record AdjustReq(UUID storeId, String period, String accountCode, Long amount, String counterCode, String date, String memo) {}

    /** 마감월 직접 조정 분개 (예: 마감 뒤 받은 카드 수수료 정산, 누락 매출, 환불) */
    @Transactional
    public Map<String, Object> adjust(AdjustReq r, UUID actor) {
        String memo = memo(r.memo());
        requireClosed(r.storeId(), r.period());
        if (r.amount() == null || r.amount() == 0) throw ApiException.bad("조정 금액을 입력하세요. 줄이려면 음수(−)로 입력합니다.");
        var acc = db.sql("select code, name, type, pnl_section from stl.account_subject where code = ? and is_active").param(r.accountCode()).query().listOfRows();
        if (acc.isEmpty() || acc.get(0).get("pnl_section") == null) throw ApiException.bad("손익 계정(매출·비용·영업외)을 고르세요.");
        String counter = r.counterCode() == null || r.counterCode().isBlank() ? "103" : r.counterCode();
        if (!COUNTERS.contains(counter)) throw ApiException.bad("상대 계정은 보통예금·현금·본사 미지급금·외상매입금·재고·카드 미수금 중에서 고르세요.");
        LocalDate end = SettlementService.ym(r.period()).atEndOfMonth();
        LocalDate date = r.date() == null || r.date().isBlank() ? end : LocalDate.parse(r.date());
        if (!Ledger.period(date).equals(r.period())) throw ApiException.bad("조정 일자는 " + r.period().substring(0, 4) + "-" + r.period().substring(4) + " 안이어야 합니다.");
        long amt = r.amount();
        boolean revenue = "REVENUE".equals(acc.get(0).get("type"));
        String desc = "마감 후 조정 " + acc.get(0).get("name") + " " + (amt > 0 ? "+" : "") + amt;
        UUID id = UUID.randomUUID();
        List<Line> ls = revenue
                ? List.of(Line.dr(counter, amt, null, memo), Line.cr(r.accountCode(), amt, null, memo))
                : List.of(Line.dr(r.accountCode(), amt, null, memo), Line.cr(counter, amt, null, memo));
        UUID entry = ledger.postInto(r.storeId(), date, "CLOSE_ADJ", id, desc, ls, actor);
        refreshWeeks(r.storeId(), date);
        return revise(r.storeId(), r.period(), "ADJUST", r.accountCode(), counter, amt, date, desc, memo, entry, actor);
    }

    /** 다시 계산만 (예: 로열티 정책을 소급 수정한 경우) */
    @Transactional
    public Map<String, Object> recalc(UUID store, String period, String memo, UUID actor) {
        memo = memo(memo);
        requireClosed(store, period);
        return revise(store, period, "RECALC", null, null, 0, null, "결산 다시 계산", memo, null, actor);
    }

    /** 결산 재계산 → 로열티 차액 분개·본사 청구 → 새 버전 저장 */
    Map<String, Object> revise(UUID store, String period, String kind, String account, String counter, long amount, LocalDate date,
                               String desc, String memo, UUID entryId, UUID actor) {
        var pre = stl.compute(store, period, false);
        long[] roy = stl.royaltyFor(store, period, (Long) pre.get("net_sales"), (Long) pre.get("sales_vat"), (Long) pre.get("gross_sales"));
        long postedSupply = stl.sum("select coalesce(sum(supply_amount),0) from stl.hq_charge where store_id = ? and period = ? and charge_type = 'ROYALTY'", store, period);
        long postedVat = stl.sum("select coalesce(sum(vat_amount),0) from stl.hq_charge where store_id = ? and period = ? and charge_type = 'ROYALTY'", store, period);
        long diff = roy[0] - postedSupply, vatDiff = roy[1] - postedVat;
        if (diff != 0 || vatDiff != 0) {
            LocalDate end = SettlementService.ym(period).atEndOfMonth();
            UUID src = UUID.randomUUID();
            ledger.postInto(store, end, "ROYALTY_ADJ", src, "로열티 수정 결산 차액 " + period, List.of(
                    Line.dr("870", diff, null, "로열티 차액"), Line.dr("135", vatDiff, null, "매입세액 차액"), Line.cr("253", diff + vatDiff, null, "본사 청구 차액")), actor);
            db.sql("insert into stl.hq_charge(store_id, period, charge_type, description, ref_type, ref_id, tax_type, supply_amount, vat_amount, total_amount) values (?,?, 'ROYALTY', ?, 'ROYALTY_ADJ', ?, 'TAXABLE', ?,?,?)")
                    .params(store, period, "로열티 수정 결산 차액 " + period, src, diff, vatDiff, diff + vatDiff).update();
        }
        var m = stl.compute(store, period, false);
        int ver = stl.saveSummary(store, period, m, actor, memo);
        db.sql("""
                insert into stl.close_adjustment(store_id, period, kind, account_code, counter_code, amount, entry_date, description, memo, entry_id, royalty_diff, version, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(store, period, kind, account, counter, amount, date, desc, memo, entryId, diff, ver, actor).update();
        stl.audit(actor, "PERIOD_REVISE", store, period);
        m.put("version", ver);
        m.put("royalty_diff", diff);
        return m;
    }

    /** 바뀐 날짜가 속한 주의 자동 주 마감 스냅샷을 지움 → 다음 자동 마감(1시간 주기)에서 다시 확정 */
    void refreshWeeks(UUID store, LocalDate... dates) {
        LocalDate thisMon = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).with(DayOfWeek.MONDAY);
        for (LocalDate d : dates) {
            LocalDate mon = d.with(DayOfWeek.MONDAY);
            if (mon.isBefore(thisMon)) db.sql("delete from stl.week_close where store_id = ? and week_start = ? and auto").params(store, mon).update();
        }
    }
}
