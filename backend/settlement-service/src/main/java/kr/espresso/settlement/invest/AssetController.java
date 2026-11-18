package kr.espresso.settlement.invest;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.settlement.ledger.Ledger;
import kr.espresso.settlement.ledger.Ledger.Line;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 설비·비품 자산 (설비 카테고리 + 기준 단가 이상 고가 식기).
 * 파손·폐기: 장부가액 = 취득가 - 감가상각누계 → 유형자산처분손실(970)
 */
@RestController
@RequestMapping("/api/stl/assets")
public class AssetController {
    private final JdbcClient db;
    private final Ledger ledger;
    private final CoreClient core;

    public AssetController(JdbcClient db, Ledger ledger, CoreClient core) { this.db = db; this.ledger = ledger; this.core = core; }

    static final String LIST = """
            select a.id, a.store_id, a.name, a.category_code, coalesce(a.division_code, 'MANAGEMENT') division_code, a.acquired_date, a.cost,
              a.useful_life_months, a.source_type, a.disposed_date, a.dispose_reason,
              coalesce((select sum(l.debit) - sum(l.credit) from stl.journal_line l join stl.journal_entry e on e.id = l.entry_id and e.entry_date = l.entry_date
                         where e.source_type = 'DEPRECIATION' and e.source_id = a.id and l.account_code = '818'), 0) accumulated
            from stl.fixed_asset a where a.store_id = ? order by a.disposed_date nulls first, a.acquired_date desc, a.name""";

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam UUID storeId) {
        CurrentUser u = CurrentUser.get();
        core.requireOn(u, storeId, Roles.OWNER);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : db.sql(LIST).param(storeId).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("book_value", ((Number) r.get("cost")).longValue() - ((Number) r.get("accumulated")).longValue());
            out.add(m);
        }
        return out;
    }

    public record DisposeReq(LocalDate date, String reason) {}

    @PostMapping("/{id}/dispose")
    @Transactional
    public Map<String, Object> dispose(@PathVariable UUID id, @RequestBody DisposeReq r) {
        CurrentUser u = CurrentUser.get();
        var a = db.sql("select * from stl.fixed_asset where id = ? for update").param(id).query().listOfRows();
        if (a.isEmpty()) throw ApiException.notFound("자산");
        var asset = a.get(0);
        UUID store = (UUID) asset.get("store_id");
        core.requireOn(u, store, Roles.OWNER);
        if (asset.get("disposed_date") != null) throw ApiException.conflict("이미 폐기 처리된 자산입니다.");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.bad("폐기 사유(예: 파손)를 입력하세요.");
        LocalDate d = r.date() == null ? LocalDate.now() : r.date();
        if (d.isAfter(LocalDate.now())) throw ApiException.bad("미래 날짜로 폐기할 수 없습니다.");
        Object aq = asset.get("acquired_date");
        LocalDate acquired = aq instanceof java.sql.Date sd ? sd.toLocalDate() : LocalDate.parse(aq.toString());
        if (d.isBefore(acquired)) throw ApiException.bad("취득일 이전으로 폐기할 수 없습니다.");
        long cost = ((Number) asset.get("cost")).longValue();
        long acc = db.sql("""
                select coalesce(sum(l.debit) - sum(l.credit), 0) from stl.journal_line l join stl.journal_entry e on e.id = l.entry_id and e.entry_date = l.entry_date
                where e.source_type = 'DEPRECIATION' and e.source_id = ? and l.account_code = '818'""").param(id).query(Long.class).single();
        long book = cost - acc;
        String div = Objects.requireNonNullElse((String) asset.get("division_code"), "MANAGEMENT");
        String name = (String) asset.get("name");
        ledger.post(store, d, "ASSET_DISPOSAL", id, "비품 폐기 " + name + " (" + r.reason().trim() + ")", List.of(
                Line.dr("207", acc, div, "감가상각누계 제거"),
                Line.dr("970", book, div, "처분손실(장부가액)"),
                Line.cr("206", cost, div, name)), u.id());
        db.sql("update stl.fixed_asset set disposed_date = ?, dispose_reason = ?, disposed_by = ? where id = ?")
                .params(d, r.reason().trim(), u.id(), id).update();
        return Map.of("ok", true, "bookValue", book, "accumulated", acc);
    }
}
