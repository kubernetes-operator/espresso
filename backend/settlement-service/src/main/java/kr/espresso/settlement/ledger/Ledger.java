package kr.espresso.settlement.ledger;

import kr.espresso.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 복식부기 분개 기록 (차변 합 = 대변 합 검증) */
@Component
public class Ledger {
    private final JdbcClient db;
    public Ledger(JdbcClient db) { this.db = db; }

    public record Line(String account, long debit, long credit, String division, String memo) {
        public static Line dr(String acc, long amt, String div, String memo) { return new Line(acc, amt, 0, div, memo); }
        public static Line cr(String acc, long amt, String div, String memo) { return new Line(acc, 0, amt, div, memo); }
    }

    public static String period(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("yyyyMM")); }

    public boolean isClosed(UUID store, String period) {
        return db.sql("select count(*) from stl.period_close where store_id = ? and period = ? and status = 'CLOSED'").params(store, period).query(Integer.class).single() > 0;
    }

    public UUID post(UUID store, LocalDate date, String sourceType, UUID sourceId, String desc, List<Line> lines, UUID actor) {
        String period = period(date);
        boolean adj = isClosed(store, period);
        if (adj) { date = LocalDate.now(); period = period(date); }   // 마감 후 발생분은 현재 기간 조정 분개
        List<Line> ls = lines.stream().filter(l -> l.debit() != 0 || l.credit() != 0).map(l -> {
            // 음수 금액은 반대 변으로
            if (l.debit() < 0) return new Line(l.account(), 0, -l.debit(), l.division(), l.memo());
            if (l.credit() < 0) return new Line(l.account(), -l.credit(), 0, l.division(), l.memo());
            return l;
        }).toList();
        long dr = ls.stream().mapToLong(Line::debit).sum(), cr = ls.stream().mapToLong(Line::credit).sum();
        if (dr != cr) throw new ApiException(500, "UNBALANCED", "분개 차대 불일치 " + dr + " / " + cr + " (" + desc + ")");
        if (ls.isEmpty()) return null;
        UUID id = UUID.randomUUID();
        db.sql("insert into stl.journal_entry(id, store_id, entry_date, period, source_type, source_id, description, is_adjustment, created_by) values (?,?,?,?,?,?,?,?,?)")
                .params(id, store, date, period, sourceType, sourceId, desc, adj, actor).update();
        for (Line l : ls)
            db.sql("insert into stl.journal_line(entry_id, entry_date, store_id, period, account_code, debit, credit, division_code, memo) values (?,?,?,?,?,?,?,?,?)")
                    .params(id, date, store, period, l.account(), l.debit(), l.credit(), l.division(), l.memo()).update();
        return id;
    }

    /** 원천 문서의 분개를 원래 일자로 역분개 (원 기간이 마감됐으면 post 가 현재 기간 조정 분개로 처리) */
    public void reverse(String sourceType, UUID sourceId, String desc, UUID actor) { reverse(sourceType, sourceId, null, desc, actor); }

    public void reverse(String sourceType, UUID sourceId, String period, String desc, UUID actor) {
        for (var e : db.sql("select id, store_id, entry_date from stl.journal_entry where source_type = ? and source_id = ? and (cast(? as varchar) is null or period = cast(? as varchar))")
                .params(sourceType, sourceId, period, period).query().listOfRows()) {
            var lines = db.sql("select account_code, debit, credit, division_code from stl.journal_line where entry_id = ?").param(e.get("id")).query()
                    .listOfRows().stream().map(l -> new Line((String) l.get("account_code"), ((Number) l.get("credit")).longValue(),
                            ((Number) l.get("debit")).longValue(), (String) l.get("division_code"), "역분개")).toList();
            LocalDate d = ((java.sql.Date) e.get("entry_date")).toLocalDate();
            post((UUID) e.get("store_id"), d, sourceType + "_REVERSAL", sourceId, desc, lines, actor);
            db.sql("update stl.journal_entry set source_type = source_type || '_VOID' where id = ?").param(e.get("id")).update();
        }
    }
}
