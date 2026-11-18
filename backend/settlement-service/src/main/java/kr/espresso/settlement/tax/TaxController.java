package kr.espresso.settlement.tax;

import kr.espresso.common.*;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 세무 연계 (설계 §8.6): 전표 내보내기, 부가세 집계, 전자세금계산서 대기열 */
@RestController
@RequestMapping("/api/stl/tax")
public class TaxController {
    private final JdbcClient db;
    private final CoreClient core;

    public TaxController(JdbcClient db, CoreClient core) { this.db = db; this.core = core; }

    /** 분기 부가세 집계: 매출세액(255) − 매입세액(135) */
    @GetMapping("/vat-summary")
    public List<Map<String, Object>> vat(@RequestParam int year, @RequestParam int quarter) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        String from = String.format("%d%02d", year, (quarter - 1) * 3 + 1), to = String.format("%d%02d", year, quarter * 3);
        Map<String, String> names = new HashMap<>();
        core.allStores().forEach(s -> names.put(s.get("id").toString(), (String) s.get("name")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : db.sql("""
                select store_id, sum(case when account_code = '255' then credit - debit else 0 end) output_vat,
                       sum(case when account_code = '135' then debit - credit else 0 end) input_vat,
                       sum(case when account_code in ('401','402','403') then credit - debit else 0 end) sales_supply
                from stl.journal_line where period between ? and ? group by store_id""").params(from, to).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("store_name", names.getOrDefault(r.get("store_id").toString(), ""));
            m.put("payable", ((Number) r.get("output_vat")).longValue() - ((Number) r.get("input_vat")).longValue());
            out.add(m);
        }
        return out;
    }

    /** 전표 내보내기: 더존 업로드용 열 순서(일자/차대/계정코드/외부코드/계정명/금액/적요/매장) CSV — 엑셀에서 바로 열림 */
    @GetMapping("/export/journal.csv")
    @Transactional
    public ResponseEntity<byte[]> export(@RequestParam String from, @RequestParam String to, @RequestParam(required = false) UUID storeId) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        Map<String, String> names = new HashMap<>();
        core.allStores().forEach(s -> names.put(s.get("id").toString(), (String) s.get("name")));
        StringBuilder sb = new StringBuilder("﻿일자,전표번호,구분,계정코드,외부코드,계정명,금액,적요,매장,구분(사업)\n");
        int no = 0; Object last = null;
        for (var r : db.sql("""
                select e.id, e.entry_date, e.description, e.store_id, l.account_code, a.external_code, a.name, l.debit, l.credit, l.division_code
                from stl.journal_entry e join stl.journal_line l on l.entry_id = e.id and l.entry_date = e.entry_date
                join stl.account_subject a on a.code = l.account_code
                where e.period between ? and ? and (cast(? as uuid) is null or e.store_id = ?) order by e.entry_date, e.created_at, l.id""")
                .params(from, to, storeId, storeId).query().listOfRows()) {
            if (!r.get("id").equals(last)) { no++; last = r.get("id"); }
            long dr = ((Number) r.get("debit")).longValue();
            sb.append(r.get("entry_date")).append(',').append(no).append(',').append(dr > 0 ? "차변" : "대변").append(',')
                    .append(r.get("account_code")).append(',').append(Objects.toString(r.get("external_code"), "")).append(',')
                    .append(csv((String) r.get("name"))).append(',').append(dr > 0 ? dr : r.get("credit")).append(',')
                    .append(csv((String) r.get("description"))).append(',').append(csv(names.getOrDefault(r.get("store_id").toString(), ""))).append(',')
                    .append(Objects.toString(r.get("division_code"), "")).append('\n');
        }
        db.sql("insert into stl.accounting_export(target, period_from, period_to, store_ids, requested_by, completed_at) values ('DOUZONE', ?, ?, ?, ?, now())")
                .params(from, to, storeId == null ? null : new UUID[]{storeId}, u.id()).update();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=journal-" + from + "-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8").body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String csv(String s) { return s == null ? "" : "\"" + s.replace("\"", "\"\"") + "\""; }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices() {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        return db.sql("select * from stl.tax_invoice order by created_at desc limit 200").query().listOfRows();
    }

    /** 정산서 기준 전자세금계산서 발행 요청 (발행 대행사 연동 전: 대기열에 등록 → 수동 발행 후 승인번호 입력) */
    @PostMapping("/invoices/from-statement/{statementId}")
    @Transactional
    public Map<String, Object> fromStatement(@PathVariable UUID statementId) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        var s = db.sql("select * from stl.hq_statement where id = ?").param(statementId).query().singleRow();
        if (s.get("tax_invoice_id") != null) throw ApiException.conflict("이미 세금계산서 요청이 있습니다.");
        var store = core.allStores().stream().filter(x -> x.get("id").toString().equals(s.get("store_id").toString())).findFirst().orElseThrow();
        UUID id = UUID.randomUUID();
        db.sql("""
                insert into stl.tax_invoice(id, direction, store_id, supplier_biz_no, supplier_name, buyer_biz_no, buyer_name, write_date, tax_type,
                  supply_amount, vat_amount, total_amount, status, matched_type, matched_id)
                values (?, 'ISSUE', ?, '000-00-00000', '본사', ?, ?, current_date, 'TAXABLE', ?,?,?, 'PENDING', 'HQ_STATEMENT', ?)""")
                .params(id, s.get("store_id"), Objects.toString(store.get("biz_reg_no"), "미등록"), Objects.toString(store.get("biz_name"), store.get("name").toString()),
                        s.get("supply_amount"), s.get("vat_amount"), s.get("total_amount"), statementId).update();
        db.sql("update stl.hq_statement set tax_invoice_id = ? where id = ?").params(id, statementId).update();
        return Map.of("id", id, "note", "발행 대행사가 연동되지 않아 대기 상태로 등록했습니다.");
    }

    @PostMapping("/invoices/{id}/approval-no")
    public Map<String, Object> approvalNo(@PathVariable UUID id, @RequestBody Map<String, String> b) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("update stl.tax_invoice set nts_approval_no = ?, status = 'ISSUED' where id = ?").params(b.get("approvalNo"), id).update();
        return Map.of("ok", true);
    }
}
