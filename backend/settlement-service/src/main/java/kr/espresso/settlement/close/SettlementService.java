package kr.espresso.settlement.close;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.common.ApiException;
import kr.espresso.common.Vat;
import kr.espresso.settlement.ledger.Ledger;
import kr.espresso.settlement.ledger.Ledger.Line;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 월 결산 (요구 11): 판매 수익 기준
 *   순매출 − 매출원가 − 판관비 = 영업이익,  결산액 = 영업이익 − 로열티 ± 영업외
 * 재고 장부(146) 기준: 기초 + 매입 − 손실·결손 − 판매소진 = 기말
 */
@Service
public class SettlementService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final ObjectMapper om;

    public SettlementService(JdbcClient db, Ledger ledger, ObjectMapper om) { this.db = db; this.ledger = ledger; this.om = om; }

    static YearMonth ym(String period) { return YearMonth.of(Integer.parseInt(period.substring(0, 4)), Integer.parseInt(period.substring(4))); }

    long sum(String sql, Object... params) {
        Long v = db.sql(sql).params(params).query(Long.class).single();
        return v == null ? 0 : v;
    }

    /** 마감된 기간은 저장값, 아니면 실시간 계산 (로열티·감가상각은 예상값 포함) */
    public Map<String, Object> summary(UUID store, String period) {
        boolean closed = ledger.isClosed(store, period);
        if (closed) {
            var r = db.sql("select * from stl.settlement_summary where store_id = ? and period = ? order by version desc limit 1").params(store, period).query().listOfRows();
            if (!r.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>(r.get(0));
                m.put("closed", true);
                m.put("by_division", parse(m.get("by_division")));
                m.put("ratios", ratios(n(m, "net_sales"), n(m, "cogs"), n(m, "labor"), n(m, "rent"), n(m, "stock_loss"), n(m, "settlement_amount")));
                return m;
            }
        }
        Map<String, Object> m = compute(store, period, !closed);
        m.put("closed", closed);
        return m;
    }

    static long n(Map<String, Object> m, String k) { return m.get(k) instanceof Number x ? x.longValue() : 0; }

    Object parse(Object o) {
        try { return o == null ? Map.of() : om.readValue(o.toString(), Map.class); } catch (Exception e) { return Map.of(); }
    }

    Map<String, Object> compute(UUID store, String period, boolean includeProjected) {
        String sec = """
                select coalesce(sum(case when a.type = 'REVENUE' then l.credit - l.debit else l.debit - l.credit end), 0)
                from stl.journal_line l join stl.account_subject a on a.code = l.account_code
                where l.store_id = ? and l.period = ? and a.pnl_section = ?""";
        Map<String, Long> s = new LinkedHashMap<>();
        for (String k : List.of("SALES", "COGS", "LABOR", "RENT", "UTILITY", "INTERNAL_USE", "OPERATING", "STOCK_LOSS", "DEPRECIATION", "ROYALTY", "NON_OP_INCOME", "NON_OP_EXPENSE"))
            s.put(k, sum(sec, store, period, k));
        long salesVat = sum("select coalesce(sum(credit - debit),0) from stl.journal_line l join stl.journal_entry e on e.id = l.entry_id and e.entry_date = l.entry_date where l.store_id = ? and l.period = ? and l.account_code = '255' and e.source_type like 'DAILY_SALES%'", store, period);
        long grossSales = sum("select coalesce(sum(credit - debit),0) from stl.journal_line where store_id = ? and period = ? and account_code in ('401','402','403')", store, period);
        long purchases = sum("select coalesce(sum(debit),0) from stl.journal_line where store_id = ? and period = ? and account_code = '146'", store, period);
        long stockOut = sum("select coalesce(sum(credit),0) from stl.journal_line where store_id = ? and period = ? and account_code = '146'", store, period);
        String end = ym(period).atEndOfMonth().toString();
        long closing = sum("select coalesce(sum(debit - credit),0) from stl.journal_line where store_id = ? and entry_date <= cast(? as date) and account_code = '146'", store, end);
        long opening = closing - purchases + stockOut;
        long directPurchase = sum("select coalesce(sum(l.debit - l.credit),0) from stl.journal_line l join stl.journal_entry e on e.id = l.entry_id and e.entry_date = l.entry_date where l.store_id = ? and l.period = ? and l.account_code = '451' and e.source_type like 'LOGISTICS%'", store, period);

        long royalty = s.get("ROYALTY"), depreciation = s.get("DEPRECIATION");
        boolean projected = false;
        if (includeProjected) {
            if (royalty == 0) { royalty = royaltyFor(store, period, s.get("SALES"), salesVat, grossSales)[0]; projected = royalty != 0; }
            if (depreciation == 0) { depreciation = depreciationFor(store, period).values().stream().mapToLong(Long::longValue).sum(); projected |= depreciation != 0; }
        }
        long netSales = s.get("SALES");
        long gross = netSales - s.get("COGS");
        long sga = s.get("LABOR") + s.get("RENT") + s.get("UTILITY") + s.get("INTERNAL_USE") + s.get("OPERATING") + s.get("STOCK_LOSS") + depreciation;
        long operating = gross - sga;
        long nonOp = s.get("NON_OP_INCOME") - s.get("NON_OP_EXPENSE");
        long result = operating - royalty + nonOp;

        Map<String, Object> byDiv = new LinkedHashMap<>();
        for (var r : db.sql("""
                select coalesce(division_code, 'NONE') d,
                  sum(case when account_code in ('401','402','403','404','405','406') then credit - debit else 0 end) sales,
                  sum(case when account_code in ('146') then debit else 0 end) purchases,
                  sum(case when account_code in ('451') then debit - credit else 0 end) cogs,
                  sum(case when account_code in ('861','862','863') then debit - credit else 0 end) loss,
                  sum(case when account_code in ('830') then debit - credit else 0 end) supplies
                from stl.journal_line where store_id = ? and period = ? group by 1""").params(store, period).query().listOfRows())
            byDiv.put((String) r.get("d"), Map.of("sales", r.get("sales"), "purchases", r.get("purchases"), "cogs", r.get("cogs"), "loss", r.get("loss"), "supplies", r.get("supplies")));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store_id", store); m.put("period", period);
        m.put("net_sales", netSales); m.put("sales_vat", salesVat); m.put("gross_sales", grossSales);
        m.put("cogs", s.get("COGS")); m.put("opening_stock", opening); m.put("purchases", purchases); m.put("stock_out", stockOut); m.put("closing_stock", closing);
        m.put("direct_purchase", directPurchase);
        m.put("gross_profit", gross);
        m.put("labor", s.get("LABOR")); m.put("rent", s.get("RENT")); m.put("utility", s.get("UTILITY")); m.put("internal_use", s.get("INTERNAL_USE"));
        m.put("operating_exp", s.get("OPERATING")); m.put("stock_loss", s.get("STOCK_LOSS")); m.put("depreciation", depreciation);
        m.put("operating_profit", operating); m.put("royalty", royalty); m.put("non_operating", nonOp); m.put("settlement_amount", result);
        m.put("by_division", byDiv); m.put("projected", projected);
        m.put("ratios", ratios(netSales, s.get("COGS"), s.get("LABOR"), s.get("RENT"), s.get("STOCK_LOSS"), result));
        return m;
    }

    static Map<String, Object> ratios(long sales, long cogs, long labor, long rent, long loss, long result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cogs", pct(cogs, sales)); r.put("labor", pct(labor, sales)); r.put("rent", pct(rent, sales));
        r.put("loss", pct(loss, sales)); r.put("margin", pct(result, sales));
        return r;
    }
    static Double pct(long a, long b) { return b == 0 ? null : Math.round(a * 1000.0 / b) / 10.0; }

    /** [supply, vat, base] */
    public long[] royaltyFor(UUID store, String period, long netSales, long salesVat, long grossSales) {
        LocalDate end = ym(period).atEndOfMonth();
        var rows = db.sql("select * from stl.royalty_policy where store_id = ? and valid_from <= ? and (valid_to is null or valid_to >= ?) order by valid_from desc limit 1")
                .params(store, end, ym(period).atDay(1)).query().listOfRows();
        if (rows.isEmpty()) return new long[]{0, 0, 0};
        var p = rows.get(0);
        String base = (String) p.get("base");
        long b = switch (base) {
            case "NET_SALES_INCL_VAT" -> netSales + salesVat;
            case "GROSS_SALES_EXCL_VAT" -> grossSales;
            case "GROSS_SALES_INCL_VAT" -> grossSales + salesVat;
            default -> netSales;
        };
        BigDecimal rate = (BigDecimal) p.get("rate");
        long fixed = p.get("fixed_amount") == null ? 0 : ((Number) p.get("fixed_amount")).longValue();
        long v = switch ((String) p.get("type")) {
            case "RATE" -> pctOf(b, rate);
            case "FIXED" -> fixed;
            case "RATE_PLUS_FIXED" -> pctOf(b, rate) + fixed;
            case "TIERED" -> tiered(b, p.get("tiers"));
            default -> 0;
        };
        if (p.get("min_amount") != null) v = Math.max(v, ((Number) p.get("min_amount")).longValue());
        if (p.get("max_amount") != null) v = Math.min(v, ((Number) p.get("max_amount")).longValue());
        if ("NONE".equals(p.get("type"))) v = 0;
        var a = Vat.fromSupply(Math.max(v, 0), Vat.TAXABLE);
        return new long[]{a.supply(), a.vat(), b};
    }

    static long pctOf(long base, BigDecimal rate) {
        return rate == null ? 0 : BigDecimal.valueOf(base).multiply(rate).setScale(0, RoundingMode.FLOOR).longValue();
    }

    long tiered(long base, Object tiers) {
        try {
            JsonNode arr = om.readTree(tiers.toString());
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode t : arr) {
                long from = t.path("from").asLong(), to = t.path("to").isMissingNode() || t.path("to").isNull() ? Long.MAX_VALUE : t.path("to").asLong();
                if (base <= from) break;
                long part = Math.min(base, to) - from;
                total = total.add(BigDecimal.valueOf(part).multiply(t.path("rate").decimalValue()));
            }
            return total.setScale(0, RoundingMode.FLOOR).longValue();
        } catch (Exception e) { return 0; }
    }

    /** 정액법 월 감가상각 {assetId: amount} */
    Map<UUID, Long> depreciationFor(UUID store, String period) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        LocalDate end = ym(period).atEndOfMonth();
        for (var a : db.sql("select * from stl.fixed_asset where store_id = ? and acquired_date <= ? and (disposed_date is null or disposed_date > ?)").params(store, end, end).query().listOfRows()) {
            long base = ((Number) a.get("cost")).longValue() - ((Number) a.get("residual_value")).longValue();
            int life = ((Number) a.get("useful_life_months")).intValue();
            long monthly = base / life;
            long done = sum("select coalesce(sum(l.debit),0) from stl.journal_line l join stl.journal_entry e on e.id = l.entry_id and e.entry_date = l.entry_date where e.source_type = 'DEPRECIATION' and e.source_id = ? and l.account_code = '818'", a.get("id"));
            long amt = Math.min(monthly, base - done);
            if (amt > 0) out.put((UUID) a.get("id"), amt);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> close(UUID store, String period, UUID actor) {
        if (ledger.isClosed(store, period)) throw ApiException.conflict(period + " 은(는) 이미 마감되었습니다.");
        if (ym(period).isAfter(YearMonth.now())) throw ApiException.bad("미래 기간은 마감할 수 없습니다.");
        LocalDate end = ym(period).atEndOfMonth();
        if (end.isAfter(LocalDate.now())) end = LocalDate.now();
        var pre = compute(store, period, false);
        long[] roy = royaltyFor(store, period, (Long) pre.get("net_sales"), (Long) pre.get("sales_vat"), (Long) pre.get("gross_sales"));
        if (roy[0] > 0 && sum("select count(*) from stl.journal_entry where store_id = ? and period = ? and source_type = 'ROYALTY'", store, period) == 0) {
            UUID src = UUID.nameUUIDFromBytes((store + ":" + period + ":ROYALTY").getBytes());
            ledger.post(store, end, "ROYALTY", src, "로열티 " + period + " (기준액 " + roy[2] + ")", List.of(
                    Line.dr("870", roy[0], null, "로열티"), Line.dr("135", roy[1], null, "매입세액"), Line.cr("253", roy[0] + roy[1], null, "본사 청구")), actor);
            db.sql("insert into stl.hq_charge(store_id, period, charge_type, description, ref_type, ref_id, tax_type, supply_amount, vat_amount, total_amount) values (?,?, 'ROYALTY', ?, 'ROYALTY', ?, 'TAXABLE', ?,?,?)")
                    .params(store, period, "로열티 " + period, src, roy[0], roy[1], roy[0] + roy[1]).update();
        }
        for (var e : depreciationFor(store, period).entrySet()) {
            String div = db.sql("select coalesce(division_code, 'MANAGEMENT') from stl.fixed_asset where id = ?").param(e.getKey()).query(String.class).single();
            ledger.post(store, end, "DEPRECIATION", e.getKey(), "감가상각 " + period, List.of(Line.dr("818", e.getValue(), div, "감가상각"), Line.cr("207", e.getValue(), div, "감가상각누계")), actor);
        }
        var m = compute(store, period, false);
        saveSummary(store, period, m, null, null);
        db.sql("""
                insert into stl.period_close(store_id, period, status, closed_by, closed_at) values (?,?, 'CLOSED', ?, now())
                on conflict (store_id, period) do update set status = 'CLOSED', closed_by = excluded.closed_by, closed_at = now()""").params(store, period, actor).update();
        audit(actor, "PERIOD_CLOSE", store, period);
        return m;
    }

    /** 결산 요약 저장 (새 버전). 수정 결산이면 고친 사람·사유를 남김 */
    int saveSummary(UUID store, String period, Map<String, Object> m, UUID revisedBy, String memo) {
        int ver = (int) sum("select coalesce(max(version),0) + 1 from stl.settlement_summary where store_id = ? and period = ?", store, period);
        try {
            db.sql("""
                    insert into stl.settlement_summary(store_id, period, version, net_sales, sales_vat, cogs, opening_stock, purchases, closing_stock, gross_profit,
                      labor, rent, utility, internal_use, operating_exp, stock_loss, depreciation, operating_profit, royalty, non_operating, settlement_amount, by_division, direct_purchase,
                      revised_by, revise_memo)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, cast(? as jsonb), ?, ?, ?)""")
                    .params(store, period, ver, m.get("net_sales"), m.get("sales_vat"), m.get("cogs"), m.get("opening_stock"), m.get("purchases"), m.get("closing_stock"),
                            m.get("gross_profit"), m.get("labor"), m.get("rent"), m.get("utility"), m.get("internal_use"), m.get("operating_exp"), m.get("stock_loss"),
                            m.get("depreciation"), m.get("operating_profit"), m.get("royalty"), m.get("non_operating"), m.get("settlement_amount"),
                            om.writeValueAsString(m.get("by_division")), m.get("direct_purchase"), revisedBy, memo).update();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ver;
    }

    @Transactional
    public void reopen(UUID store, String period, UUID actor) {
        if (!ledger.isClosed(store, period)) throw ApiException.conflict("마감된 기간이 아닙니다.");
        Integer issued = db.sql("select count(*) from stl.hq_charge c join stl.hq_statement s on s.id = c.statement_id where c.store_id = ? and c.period = ? and c.charge_type = 'ROYALTY' and s.status <> 'CANCELLED'")
                .params(store, period).query(Integer.class).single();
        if (issued > 0) throw ApiException.conflict("이 기간 로열티가 정산서로 발행되어 마감을 취소할 수 없습니다. 정산서를 먼저 취소하세요.");
        db.sql("update stl.period_close set status = 'OPEN', reopened_by = ?, reopened_at = now() where store_id = ? and period = ?").params(actor, store, period).update();
        UUID src = UUID.nameUUIDFromBytes((store + ":" + period + ":ROYALTY").getBytes());
        ledger.reverse("ROYALTY", src, period, "마감 취소 로열티 역분개 " + period, actor);
        db.sql("delete from stl.hq_charge where ref_id = ? and statement_id is null").param(src).update();
        // 수정 결산 로열티 차액도 되돌림 (다시 마감하면 로열티를 처음부터 계산)
        for (UUID adj : db.sql("select distinct source_id from stl.journal_entry where store_id = ? and period = ? and source_type = 'ROYALTY_ADJ'").params(store, period).query(UUID.class).list()) {
            ledger.reverse("ROYALTY_ADJ", adj, period, "마감 취소 로열티 차액 역분개 " + period, actor);
            db.sql("delete from stl.hq_charge where ref_id = ? and statement_id is null").param(adj).update();
        }
        for (UUID asset : db.sql("select distinct source_id from stl.journal_entry where store_id = ? and period = ? and source_type = 'DEPRECIATION'").params(store, period).query(UUID.class).list())
            ledger.reverse("DEPRECIATION", asset, period, "마감 취소 감가상각 역분개 " + period, actor);
        audit(actor, "PERIOD_REOPEN", store, period);
    }

    void audit(UUID actor, String action, UUID store, String period) {
        db.sql("insert into stl.audit_log(actor_id, action, target_type, target_id, after_data) values (?,?, 'STORE_PERIOD', ?, cast(? as jsonb))")
                .params(actor, action, store, "{\"period\":\"" + period + "\"}").update();
    }
}
