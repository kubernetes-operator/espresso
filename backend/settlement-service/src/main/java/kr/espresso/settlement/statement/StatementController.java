package kr.espresso.settlement.statement;

import kr.espresso.common.*;
import kr.espresso.settlement.ledger.Ledger;
import kr.espresso.settlement.ledger.Ledger.Line;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** 본사-매장 정산서 + 입금 확인 (설계 §8.5) */
@RestController
@RequestMapping("/api/stl/statements")
public class StatementController {
    private final JdbcClient db;
    private final CoreClient core;
    private final Ledger ledger;

    public StatementController(JdbcClient db, CoreClient core, Ledger ledger) { this.db = db; this.core = core; this.ledger = ledger; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID storeId, @RequestParam(required = false) String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        Set<UUID> st = storeId != null ? Set.of(storeId) : core.myStoreIds();
        if (storeId != null) core.check(storeId);
        if (st.isEmpty()) return List.of();
        db.sql("update stl.hq_statement set status = 'OVERDUE' where status in ('ISSUED','PARTIALLY_PAID') and due_date < current_date").update();
        return db.sql("""
                select s.*, s.total_amount - s.paid_amount as balance,
                  (select count(*) from stl.hq_statement_payment p where p.statement_id = s.id and p.confirmed_at is null) as unconfirmed
                from stl.hq_statement s where s.store_id in (:st) and (cast(:p as varchar) is null or s.period = cast(:p as varchar))
                order by s.period desc, s.statement_no""").param("st", st).param("p", period).query().listOfRows();
    }

    /** 아직 정산서에 묶이지 않은 본사 청구 원천 */
    @GetMapping("/pending")
    public List<Map<String, Object>> pending(@RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER);
        Set<UUID> st = storeId != null ? Set.of(storeId) : core.myStoreIds();
        if (st.isEmpty()) return List.of();
        return db.sql("""
                select store_id, period, charge_type, count(*) cnt, sum(supply_amount) supply, sum(vat_amount) vat, sum(total_amount) total
                from stl.hq_charge where statement_id is null and store_id in (:st) group by 1,2,3 order by 2,1,3""").param("st", st).query().listOfRows();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var s = load(id);
        core.check((UUID) s.get("store_id"));
        Map<String, Object> m = new LinkedHashMap<>(s);
        m.put("lines", db.sql("select * from stl.hq_statement_line where statement_id = ? order by line_type, description").param(id).query().listOfRows());
        m.put("payments", db.sql("select * from stl.hq_statement_payment where statement_id = ? order by paid_date, reported_at").param(id).query().listOfRows());
        return m;
    }

    /** 총괄: 정산서 발행 (해당 기간까지의 미청구 원천 전부) */
    @PostMapping("/issue")
    @Transactional
    public Map<String, Object> issue(@RequestBody Map<String, String> b) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        UUID store = UUID.fromString(b.get("storeId"));
        String period = b.get("period");
        var charges = db.sql("select * from stl.hq_charge where store_id = ? and period <= ? and statement_id is null order by period, created_at").params(store, period).query().listOfRows();
        if (charges.isEmpty()) throw ApiException.bad("청구할 항목이 없습니다.");
        if (db.sql("select count(*) from stl.hq_statement where store_id = ? and period = ? and status <> 'CANCELLED'").params(store, period).query(Integer.class).single() > 0)
            throw ApiException.conflict(period + " 정산서가 이미 있습니다.");
        UUID id = UUID.randomUUID();
        Integer n = db.sql("select count(*) from stl.hq_statement where statement_no like ?").param("HS-" + period + "-%").query(Integer.class).single();
        LocalDate due = LocalDate.of(Integer.parseInt(period.substring(0, 4)), Integer.parseInt(period.substring(4)), 1).plusMonths(1).withDayOfMonth(10);
        db.sql("delete from stl.hq_statement where store_id = ? and period = ? and status = 'CANCELLED'").params(store, period).update();
        db.sql("insert into stl.hq_statement(id, statement_no, store_id, period, status, due_date, issued_by, issued_at) values (?,?,?,?, 'ISSUED', ?, ?, now())")
                .params(id, String.format("HS-%s-%04d", period, n + 1), store, period, due, u.id()).update();
        long sup = 0, vat = 0, tot = 0;
        for (var c : charges) {
            db.sql("insert into stl.hq_statement_line(statement_id, line_type, description, ref_type, ref_id, tax_type, supply_amount, vat_amount, total_amount) values (?,?,?,?,?,?,?,?,?)")
                    .params(id, c.get("charge_type"), c.get("description"), c.get("ref_type"), c.get("ref_id"), c.get("tax_type"),
                            c.get("supply_amount"), c.get("vat_amount"), c.get("total_amount")).update();
            sup += ((Number) c.get("supply_amount")).longValue(); vat += ((Number) c.get("vat_amount")).longValue(); tot += ((Number) c.get("total_amount")).longValue();
            db.sql("update stl.hq_charge set statement_id = ? where id = ?").params(id, c.get("id")).update();
        }
        db.sql("update stl.hq_statement set supply_amount = ?, vat_amount = ?, total_amount = ? where id = ?").params(sup, vat, tot, id).update();
        return Map.of("id", id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        var s = load(id);
        if (((Number) s.get("paid_amount")).longValue() > 0) throw ApiException.conflict("입금 확인된 정산서는 취소할 수 없습니다.");
        db.sql("update stl.hq_charge set statement_id = null where statement_id = ?").param(id).update();
        db.sql("update stl.hq_statement set status = 'CANCELLED' where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    public record PaymentReq(Long amount, String paidDate, String method, String depositor, String memo) {}

    /** 매장(점주): 입금 등록 / 총괄이 등록하면 바로 확인 처리 */
    @PostMapping("/{id}/payments")
    @Transactional
    public Map<String, Object> report(@PathVariable UUID id, @RequestBody PaymentReq r) {
        CurrentUser u = CurrentUser.get();
        var s = load(id);
        core.requireOn(u, (UUID) s.get("store_id"), Roles.OWNER);
        if (List.of("PAID", "CANCELLED", "DRAFT").contains((String) s.get("status"))) throw ApiException.conflict("입금을 등록할 수 없는 상태입니다.");
        if (r.amount() == null || r.amount() <= 0) throw ApiException.bad("입금액을 입력하세요.");
        UUID pid = UUID.randomUUID();
        db.sql("insert into stl.hq_statement_payment(id, statement_id, amount, paid_date, method, depositor, memo, reported_by, reported_at) values (?,?,?, cast(? as date), ?,?,?,?, now())")
                .params(pid, id, r.amount(), r.paidDate() == null ? LocalDate.now().toString() : r.paidDate(), Objects.requireNonNullElse(r.method(), "TRANSFER"),
                        r.depositor(), r.memo(), u.id()).update();
        if (u.isHqAdmin()) confirm(id, pid);
        return Map.of("id", pid);
    }

    /** 총괄: 입금 확인 → 미지급금(본사) 상계 분개, 잔액·상태 갱신 */
    @PostMapping("/{id}/payments/{pid}/confirm")
    @Transactional
    public Map<String, Object> confirm(@PathVariable UUID id, @PathVariable UUID pid) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        var s = load(id);
        var p = db.sql("select * from stl.hq_statement_payment where id = ? and statement_id = ?").params(pid, id).query().singleRow();
        if (p.get("confirmed_at") != null) throw ApiException.conflict("이미 확인한 입금입니다.");
        long amt = ((Number) p.get("amount")).longValue();
        long paid = ((Number) s.get("paid_amount")).longValue() + amt;
        long total = ((Number) s.get("total_amount")).longValue();
        db.sql("update stl.hq_statement_payment set confirmed_by = ?, confirmed_at = now() where id = ?").params(u.id(), pid).update();
        String st = paid >= total ? "PAID" : "PARTIALLY_PAID";
        db.sql("update stl.hq_statement set paid_amount = ?, status = ?, paid_at = case when ? = 'PAID' then now() else paid_at end where id = ?").params(paid, st, st, id).update();
        ledger.post((UUID) s.get("store_id"), ((java.sql.Date) p.get("paid_date")).toLocalDate(), "HQ_PAYMENT", pid, "정산서 입금 " + s.get("statement_no"),
                List.of(Line.dr("253", amt, null, "본사 미지급금 상계"), Line.cr("103", amt, null, "입금")), u.id());
        return Map.of("status", st, "paid", paid);
    }

    Map<String, Object> load(UUID id) {
        var r = db.sql("select * from stl.hq_statement where id = ?").param(id).query().listOfRows();
        if (r.isEmpty()) throw ApiException.notFound("정산서");
        return r.get(0);
    }
}
