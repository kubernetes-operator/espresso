package kr.espresso.settlement.expense;

import kr.espresso.common.*;
import kr.espresso.settlement.ledger.Ledger;
import kr.espresso.settlement.ledger.Ledger.Line;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** 비용: 임대·공과금(사용료)·운영비 (요구 3). 결재는 core 결재 엔진(EXPENSE) */
@RestController
public class ExpenseController {
    static final Map<String, String> KIND = Map.of("RENT", "임대", "UTILITY", "공과금", "OPERATING", "운영비", "NON_OPERATING", "영업외");
    private final JdbcClient db;
    private final CoreClient core;
    private final Ledger ledger;

    public ExpenseController(JdbcClient db, CoreClient core, Ledger ledger) { this.db = db; this.core = core; this.ledger = ledger; }

    @GetMapping("/api/stl/accounts")
    public List<Map<String, Object>> accounts() {
        CurrentUser.get();
        return db.sql("select code, name, type, pnl_section, external_code, is_system, is_active from stl.account_subject order by sort_order").query().listOfRows();
    }

    /** 계정과목 외부 코드(더존) 매핑·이름 수정 — 총괄 (요구 18) */
    @PutMapping("/api/stl/accounts/{code}")
    public Map<String, Object> updateAccount(@PathVariable String code, @RequestBody Map<String, String> b) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("update stl.account_subject set external_code = cast(? as varchar), name = coalesce(cast(? as varchar), name) where code = ?")
                .params(blank(b.get("externalCode")), blank(b.get("name")), code).update();
        return Map.of("ok", true);
    }

    @PostMapping("/api/stl/accounts")
    public Map<String, Object> addAccount(@RequestBody Map<String, String> b) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        if (!Set.of("OPERATING", "NON_OP_INCOME", "NON_OP_EXPENSE", "UTILITY", "RENT").contains(b.get("pnlSection"))) throw ApiException.bad("손익 구분을 확인하세요.");
        String type = "NON_OP_INCOME".equals(b.get("pnlSection")) ? "REVENUE" : "EXPENSE";
        db.sql("insert into stl.account_subject(code, name, type, pnl_section, external_code, sort_order) values (?,?,?,?,?, 650)")
                .params(b.get("code"), b.get("name"), type, b.get("pnlSection"), blank(b.get("externalCode"))).update();
        return Map.of("ok", true);
    }

    static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    @GetMapping("/api/stl/expenses")
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID storeId, @RequestParam String month) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR);
        Set<UUID> st = storeId != null ? Set.of(storeId) : core.myStoreIds();
        if (storeId != null) core.check(storeId);
        if (st.isEmpty()) return List.of();
        YearMonth ym = YearMonth.parse(month);
        return db.sql("""
                select e.*, a.name account_name from stl.expense e join stl.account_subject a on a.code = e.account_code
                where e.store_id in (:st) and e.expense_date between :f and :t and e.status <> 'CANCELLED' order by e.expense_date desc, e.created_at desc""")
                .param("st", st).param("f", ym.atDay(1)).param("t", ym.atEndOfMonth()).query().listOfRows();
    }

    public record ExpenseReq(UUID storeId, String expenseKind, String accountCode, String vendorName, String expenseDate,
                             String usageFrom, String usageTo, String taxType, Long amount, String amountMode,
                             String payer, String evidenceType, String memo, Boolean submit) {}

    /** 트랜잭션 없이: 저장(자동 커밋) 후 상신해야 core 결재 엔진이 이 문서를 조회할 수 있다 */
    @PostMapping("/api/stl/expenses")
    public Map<String, Object> create(@RequestBody ExpenseReq r) {
        CurrentUser u = CurrentUser.get();
        core.requireOn(u, r.storeId(), Roles.OWNER, Roles.STORE_MGR);
        if (!KIND.containsKey(r.expenseKind())) throw ApiException.bad("비용 구분을 선택하세요.");
        if ("RENT".equals(r.expenseKind()) && !u.has(Roles.HQ_ADMIN, Roles.OWNER)) throw ApiException.forbidden("임대 비용은 점주·총괄만 입력합니다.");
        if (r.amount() == null || r.amount() <= 0) throw ApiException.bad("금액을 입력하세요.");
        requirePeriod(r.expenseKind(), r.usageFrom(), r.usageTo());
        String acc = db.sql("select type from stl.account_subject where code = ? and is_active").param(r.accountCode()).query(String.class)
                .optional().orElseThrow(() -> ApiException.bad("계정과목을 확인하세요."));
        if (!"EXPENSE".equals(acc)) throw ApiException.bad("비용 계정과목을 선택하세요.");
        String tax = Objects.requireNonNullElse(r.taxType(), Vat.TAXABLE);
        var a = "INCLUSIVE".equals(r.amountMode()) ? Vat.fromTotal(r.amount(), tax) : Vat.fromSupply(r.amount(), tax);
        UUID id = UUID.randomUUID();
        LocalDate d = r.expenseDate() == null ? LocalDate.now() : LocalDate.parse(r.expenseDate());
        db.sql("""
                insert into stl.expense(id, expense_no, store_id, expense_kind, account_code, vendor_name, expense_date, usage_from, usage_to, tax_type,
                  supply_amount, vat_amount, total_amount, payer, evidence_type, memo, created_by)
                values (?,?,?,?,?,?,?, cast(? as date), cast(? as date), ?,?,?,?,?,?,?,?)""")
                .params(id, nextNo(), r.storeId(), r.expenseKind(), r.accountCode(), r.vendorName(), d, r.usageFrom(), r.usageTo(), tax,
                        a.supply(), a.vat(), a.total(), Objects.requireNonNullElse(r.payer(), "STORE"), r.evidenceType(), r.memo(), u.id()).update();
        if (Boolean.TRUE.equals(r.submit())) submit(id);
        return Map.of("id", id);
    }

    /** 임대(월세·관리비)는 사용 기간 필수 */
    static void requirePeriod(String kind, String from, String to) {
        if (!"RENT".equals(kind)) return;
        if (from == null || from.isBlank() || to == null || to.isBlank()) throw ApiException.bad("임대 비용은 사용 기간(시작일·종료일)을 입력하세요.");
        if (LocalDate.parse(to).isBefore(LocalDate.parse(from))) throw ApiException.bad("사용 기간 종료일이 시작일보다 빠릅니다.");
    }

    /**
     * 비용 수정
     *  - 작성 중·반려: 작성 권한자(임대는 점주·총괄)가 내용 수정
     *  - 승인된 임대(월세·관리비): 점주·총괄이 금액·기간 수정 → 기존 분개 역분개 후 새 금액으로 다시 분개 (이력 보존)
     */
    @PutMapping("/api/stl/expenses/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody ExpenseReq r) {
        CurrentUser u = CurrentUser.get();
        var e = load(id);
        UUID store = (UUID) e.get("store_id");
        String kind = (String) e.get("expense_kind");
        String st = (String) e.get("status");
        boolean rent = "RENT".equals(kind);
        if (rent) core.requireOn(u, store, Roles.OWNER); else core.requireOn(u, store, Roles.OWNER, Roles.STORE_MGR);
        boolean approvedRent = rent && "APPROVED".equals(st);
        if (!approvedRent && !List.of("DRAFT", "REJECTED").contains(st)) throw ApiException.conflict("작성 중·반려된 비용(또는 승인된 임대 비용)만 수정할 수 있습니다.");
        if (approvedRent && "HQ".equals(e.get("payer"))) throw ApiException.conflict("본사 대납 임대 비용은 정산서와 연결되어 있어 취소 후 다시 입력하세요.");
        if (r.amount() == null || r.amount() <= 0) throw ApiException.bad("금액을 입력하세요.");
        String from = r.usageFrom() != null ? r.usageFrom() : e.get("usage_from") == null ? null : e.get("usage_from").toString();
        String to = r.usageTo() != null ? r.usageTo() : e.get("usage_to") == null ? null : e.get("usage_to").toString();
        requirePeriod(kind, from, to);
        String tax = Objects.requireNonNullElse(r.taxType(), (String) e.get("tax_type"));
        var a = "INCLUSIVE".equals(r.amountMode()) ? Vat.fromTotal(r.amount(), tax) : Vat.fromSupply(r.amount(), tax);
        LocalDate d = r.expenseDate() == null ? ((java.sql.Date) e.get("expense_date")).toLocalDate() : LocalDate.parse(r.expenseDate());
        if (approvedRent) ledger.reverse("EXPENSE", id, "비용 수정 역분개 " + e.get("expense_no"), u.id());
        db.sql("""
                update stl.expense set vendor_name = coalesce(cast(? as varchar), vendor_name), expense_date = ?, usage_from = cast(? as date), usage_to = cast(? as date),
                  tax_type = ?, supply_amount = ?, vat_amount = ?, total_amount = ?, account_code = coalesce(cast(? as varchar), account_code),
                  memo = case when memo like '정기:%' then memo else coalesce(cast(? as text), memo) end where id = ?""")
                .params(r.vendorName(), d, from, to, tax, a.supply(), a.vat(), a.total(), r.accountCode(), r.memo(), id).update();
        if (approvedRent) postExpense(id, u.id());
        return Map.of("ok", true, "reposted", approvedRent);
    }

    String nextNo() {
        String ym = LocalDate.now().toString().substring(0, 7).replace("-", "");
        Integer n = db.sql("select count(*) from stl.expense where expense_no like ?").param("EX-" + ym + "-%").query(Integer.class).single();
        return String.format("EX-%s-%06d", ym, n + 1);
    }

    @PostMapping("/api/stl/expenses/{id}/submit")
    public Map<String, Object> submit(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var e = load(id);
        core.requireOn(u, (UUID) e.get("store_id"), Roles.OWNER, Roles.STORE_MGR);
        if (!"DRAFT".equals(e.get("status"))) throw ApiException.conflict("작성 중인 비용만 상신할 수 있습니다.");
        db.sql("update stl.expense set status = 'REQUESTED', requested_by = ?, requested_at = now() where id = ?").params(u.id(), id).update();
        try { core.startExpenseApproval(id); }
        catch (RuntimeException ex) {   // 결재 시작 실패 → 작성 상태로 되돌림 (보상 처리)
            db.sql("update stl.expense set status = 'DRAFT', requested_at = null, requested_by = null where id = ?").param(id).update();
            throw ApiException.bad("결재를 시작하지 못했습니다: " + ex.getMessage());
        }
        return Map.of("ok", true);
    }

    @PostMapping("/api/stl/expenses/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        var e = load(id);
        core.requireOn(u, (UUID) e.get("store_id"), Roles.OWNER, Roles.STORE_MGR);
        if (!List.of("DRAFT", "REJECTED").contains((String) e.get("status"))) throw ApiException.conflict("작성 중·반려된 비용만 취소할 수 있습니다.");
        db.sql("update stl.expense set status = 'CANCELLED' where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    /** 임대 계약 기준 월세·관리비 생성 (계약에 근거한 고정비 → 결재 없이 승인 처리) */
    @PostMapping("/api/stl/expenses/recurring")
    @Transactional
    public Map<String, Object> recurring(@RequestBody Map<String, String> body) {
        CurrentUser u = CurrentUser.get();
        UUID store = UUID.fromString(body.get("storeId"));
        core.requireOn(u, store, Roles.OWNER);
        YearMonth ym = YearMonth.parse(body.get("month"));
        int made = 0;
        for (var l : core.leases(store)) {
            LocalDate start = LocalDate.parse(l.get("start_date").toString());
            LocalDate end = l.get("end_date") == null ? null : LocalDate.parse(l.get("end_date").toString());
            if (start.isAfter(ym.atEndOfMonth()) || (end != null && end.isBefore(ym.atDay(1)))) continue;
            int day = l.get("payment_day") == null ? 25 : Math.min(((Number) l.get("payment_day")).intValue(), ym.lengthOfMonth());
            String tax = Objects.toString(l.get("rent_vat_type"), "TAXABLE");
            for (Object[] it : new Object[][]{{"819", "월세", l.get("monthly_rent")}, {"837", "관리비", l.get("maintenance_fee")}}) {
                long amt = ((Number) it[2]).longValue();
                if (amt <= 0) continue;
                String memo = "정기:" + ym + ":" + it[0];
                if (db.sql("select count(*) from stl.expense where store_id = ? and memo = ? and status <> 'CANCELLED'").params(store, memo).query(Integer.class).single() > 0) continue;
                var a = Vat.fromSupply(amt, tax);
                UUID id = UUID.randomUUID();
                db.sql("""
                        insert into stl.expense(id, expense_no, store_id, expense_kind, account_code, vendor_name, expense_date, usage_from, usage_to, tax_type, supply_amount, vat_amount, total_amount,
                          payer, evidence_type, status, memo, created_by, requested_at, approved_at)
                        values (?,?,?, 'RENT', ?, ?, ?, ?, ?, ?,?,?,?, 'STORE', 'TAX_INVOICE', 'APPROVED', ?, ?, now(), now())""")
                        .params(id, nextNo(), store, it[0], Objects.toString(l.get("lessor_name"), "임대인") + " " + it[1], ym.atDay(day),
                                start.isAfter(ym.atDay(1)) ? start : ym.atDay(1), end != null && end.isBefore(ym.atEndOfMonth()) ? end : ym.atEndOfMonth(), tax,
                                a.supply(), a.vat(), a.total(), memo, u.id()).update();
                postExpense(id, u.id());
                made++;
            }
        }
        return Map.of("created", made);
    }

    Map<String, Object> load(UUID id) {
        var rows = db.sql("select * from stl.expense where id = ?").param(id).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("비용");
        return rows.get(0);
    }

    void postExpense(UUID id, UUID actor) {
        var e = load(id);
        UUID store = (UUID) e.get("store_id");
        long supply = ((Number) e.get("supply_amount")).longValue(), vat = ((Number) e.get("vat_amount")).longValue();
        boolean hq = "HQ".equals(e.get("payer"));
        String desc = e.get("expense_no") + " " + Objects.toString(e.get("vendor_name"), "");
        ledger.post(store, (LocalDate) ((java.sql.Date) e.get("expense_date")).toLocalDate(), "EXPENSE", id, desc, List.of(
                Line.dr((String) e.get("account_code"), supply, null, desc),
                Line.dr("135", vat, null, "매입세액"),
                Line.cr(hq ? "253" : "103", supply + vat, null, hq ? "본사 대납" : "지급")), actor);
        if (hq)
            db.sql("insert into stl.hq_charge(store_id, period, charge_type, description, ref_type, ref_id, tax_type, supply_amount, vat_amount, total_amount) values (?,?, 'OTHER', ?, 'EXPENSE', ?, ?,?,?,?)")
                    .params(store, Ledger.period(((java.sql.Date) e.get("expense_date")).toLocalDate()), "본사 대납 비용 " + desc, id, e.get("tax_type"), supply, vat, supply + vat).update();
    }

    // ---------------- core 결재 엔진 연동 (내부) ----------------
    @GetMapping("/internal/expenses/{id}/summary")
    public Map<String, Object> summary(@PathVariable UUID id) {
        var e = load(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", e.get("expense_no"));
        m.put("title", KIND.get(e.get("expense_kind")) + " · " + Objects.toString(e.get("vendor_name"), "") + ("HQ".equals(e.get("payer")) ? " (본사 대납)" : ""));
        m.put("storeId", e.get("store_id"));
        m.put("storeName", core.allStores().stream().filter(s -> s.get("id").toString().equals(e.get("store_id").toString())).map(s -> s.get("name")).findFirst().orElse(""));
        m.put("amount", e.get("total_amount")); m.put("status", e.get("status")); m.put("requestedBy", e.get("requested_by"));
        m.put("link", "/expenses?id=" + id);
        return m;
    }

    public record StatusReq(String status, UUID actor, String comment) {}

    @PostMapping("/internal/expenses/{id}/status")
    @Transactional
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody StatusReq r) {
        switch (r.status()) {
            case "APPROVED" -> {
                db.sql("update stl.expense set status = 'APPROVED', approved_at = now() where id = ? and status = 'REQUESTED'").param(id).update();
                postExpense(id, r.actor());
            }
            case "REJECTED" -> db.sql("update stl.expense set status = 'REJECTED', rejected_at = now(), reject_comment = ? where id = ?").params(r.comment(), id).update();
            case "DRAFT" -> db.sql("update stl.expense set status = 'DRAFT', requested_at = null where id = ?").param(id).update();
            default -> throw ApiException.bad("status");
        }
        return Map.of("ok", true);
    }
}
