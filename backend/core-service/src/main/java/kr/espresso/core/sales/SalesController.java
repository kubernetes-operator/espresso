package kr.espresso.core.sales;

import kr.espresso.common.ApiException;
import kr.espresso.common.CurrentUser;
import kr.espresso.common.Roles;
import kr.espresso.core.support.StoreScope;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/core/sales")
public class SalesController {
    private final JdbcClient db;
    private final StoreScope scope;
    private final SalesService svc;

    public SalesController(JdbcClient db, StoreScope scope, SalesService svc) { this.db = db; this.scope = scope; this.svc = svc; }

    /** 월별 일 매출 목록 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID storeId, @RequestParam String month) {
        CurrentUser u = CurrentUser.get();
        Set<UUID> st = storeId != null ? Set.of(storeId) : scope.allowed(u, List.of(Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR));
        if (storeId != null) scope.requireRoleOn(u, storeId, Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR);
        if (st.isEmpty()) return List.of();
        YearMonth ym = YearMonth.parse(month);
        return db.sql("""
                select d.*, s.name store_name,
                  (select jsonb_object_agg(x.division_code, x.supply_amount + x.vat_amount) from sales.daily_sales_division x where x.daily_sales_id = d.id)::text as division_totals
                from sales.daily_sales d join store.store s on s.id = d.store_id
                where d.store_id in (:st) and d.sales_date between :from and :to order by d.sales_date, s.code""")
                .param("st", st).param("from", ym.atDay(1)).param("to", ym.atEndOfMonth()).query().listOfRows();
    }

    /**
     * 매출 추이: 최근 12개월 월별 순매출(공급가: 과세+면세−할인−환불)과 1년 전 같은 달 순매출.
     * storeId 가 없으면 볼 수 있는 매장 합산 (본사·베이커리 제외)
     */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(required = false) UUID storeId, @RequestParam(required = false) String to) {
        CurrentUser u = CurrentUser.get();
        String[] roles = {Roles.HQ_ADMIN, Roles.OWNER, Roles.STORE_MGR, Roles.LOGISTICS_MGR, Roles.HR_MGR};
        Set<UUID> st;
        if (storeId != null) { scope.requireRoleOn(u, storeId, roles); st = Set.of(storeId); }
        else st = scope.allowed(u, List.of(roles));
        YearMonth end = to == null || to.isBlank() ? YearMonth.now(java.time.ZoneId.of("Asia/Seoul")) : YearMonth.parse(to);
        YearMonth start = end.minusMonths(23);
        Map<String, Long> byMonth = new HashMap<>(), cust = new HashMap<>();
        if (!st.isEmpty())
            for (var r : db.sql("""
                    select to_char(d.sales_date, 'YYYY-MM') m, sum(d.gross_supply + d.exempt_supply - d.discount_amount - d.refund_amount) net, sum(coalesce(d.customer_count,0)) c
                    from sales.daily_sales d join store.store s on s.id = d.store_id
                    where d.store_id in (:st) and s.site_type = 'STORE' and d.sales_date between :f and :t group by 1""")
                    .param("st", st).param("f", start.atDay(1)).param("t", end.atEndOfMonth()).query().listOfRows()) {
                byMonth.put((String) r.get("m"), ((Number) r.get("net")).longValue());
                cust.put((String) r.get("m"), ((Number) r.get("c")).longValue());
            }
        List<Map<String, Object>> months = new ArrayList<>();
        long cur = 0, prev = 0;
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            String k = ym.toString(), pk = ym.minusYears(1).toString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", k); m.put("net_sales", byMonth.get(k)); m.put("prev_year", byMonth.get(pk));
            m.put("customers", cust.get(k));
            cur += byMonth.getOrDefault(k, 0L); prev += byMonth.getOrDefault(pk, 0L);
            months.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", months); out.put("total", cur); out.put("prev_total", prev);
        out.put("growth", prev == 0 ? null : Math.round((cur - prev) * 1000.0 / prev) / 10.0);
        return out;
    }

    @PutMapping("/{storeId}/{date}")
    public Map<String, Object> upsert(@PathVariable UUID storeId, @PathVariable String date, @RequestBody SalesService.DayReq req) {
        return Map.of("id", svc.upsert(storeId, LocalDate.parse(date), req, "MANUAL", null, CurrentUser.get()));
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id) { svc.close(id, CurrentUser.get()); return Map.of("ok", true); }

    @PostMapping("/{id}/reopen")
    public Map<String, Object> reopen(@PathVariable UUID id) { svc.reopen(id, CurrentUser.get()); return Map.of("ok", true); }

    static final String[] COLS = {"일자", "음료", "디저트", "식기·포장", "기타", "면세", "할인", "환불", "카드", "현금", "기타결제", "고객수"};

    /** 머리글 행으로 열 위치를 찾는다 → 열 순서가 달라도, 식기·포장 열이 없는 예전 양식(11열)도 읽힘 */
    static int[] headerIndex(String[] h) {
        int[] idx = new int[COLS.length];
        for (int k = 0; k < COLS.length; k++) {
            idx[k] = -1;
            String want = norm(COLS[k]);
            for (int j = 0; j < h.length; j++) if (h[j] != null && norm(h[j]).equals(want)) { idx[k] = j; break; }
        }
        if (idx[0] < 0) idx[0] = 0;
        return idx;
    }
    static String norm(String s) { return s.replace("\uFEFF", "").replaceAll("[\\s·/ㆍ.\"]", ""); }

    @GetMapping("/template.csv")
    public ResponseEntity<byte[]> template() {
        String csv = "﻿" + String.join(",", COLS) + "\n" + LocalDate.now().minusDays(1) + ",850000,320000,42000,15000,0,12000,0,1092000,123000,0,214\n";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sales-template.csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8").body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 엑셀(.xlsx) 또는 CSV 업로드: 금액은 VAT 포함 결제 금액. close=true 이면 바로 마감 */
    @PostMapping("/{storeId}/upload")
    public Map<String, Object> upload(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean close) throws Exception {
        CurrentUser u = CurrentUser.get();
        scope.requireRoleOn(u, storeId, Roles.OWNER, Roles.STORE_MGR, Roles.HQ_ADMIN);
        List<String[]> rows = new ArrayList<>();
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sh = wb.getSheetAt(0);
                DataFormatter fmt = new DataFormatter();
                for (Row row : sh) {
                    String[] a = new String[COLS.length];
                    for (int i = 0; i < COLS.length; i++) {
                        Cell c = row.getCell(i);
                        if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c))
                            a[i] = c.getLocalDateTimeCellValue().toLocalDate().toString();
                        else a[i] = c == null ? "" : fmt.formatCellValue(c);
                    }
                    rows.add(a);
                }
            }
        } else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) rows.add(line.replace("﻿", "").split(",", -1));
            }
        }
        UUID batch = UUID.randomUUID();
        db.sql("insert into sales.import_batch(id, store_id, source, row_count, uploaded_by) values (?,?, 'EXCEL', ?, ?)").params(batch, storeId, rows.size(), u.id()).update();
        int ok = 0; List<String> errors = new ArrayList<>();
        int[] idx = new int[COLS.length];
        for (int k = 0; k < idx.length; k++) idx[k] = k;
        for (int i = 0; i < rows.size(); i++) {
            String[] a = rows.get(i);
            if (a.length == 0 || a[0] == null || a[0].isBlank()) continue;
            if (norm(a[0]).startsWith("일자")) { idx = headerIndex(a); continue; }
            try {
                final int[] ix = idx;
                Long cust = n(a, ix[11]);
                svc.upsert(storeId, LocalDate.parse(a[ix[0]].trim().replace('.', '-').replace('/', '-')), new SalesService.DayReq(
                        n(a, ix[1]), n(a, ix[2]), n(a, ix[3]), n(a, ix[4]), n(a, ix[5]), n(a, ix[6]), n(a, ix[7]), n(a, ix[8]), n(a, ix[9]), n(a, ix[10]),
                        cust == null ? null : cust.intValue(), "업로드", close), "EXCEL", batch, u);
                ok++;
            } catch (Exception e) { errors.add((i + 1) + "행: " + e.getMessage()); }
        }
        db.sql("update sales.import_batch set status = ?, error_count = ?, applied_at = now() where id = ?")
                .params(errors.isEmpty() ? "APPLIED" : "FAILED", errors.size(), batch).update();
        return Map.of("applied", ok, "errors", errors);
    }

    static Long n(String[] a, int i) {
        if (i < 0 || a.length <= i || a[i] == null || a[i].isBlank()) return null;
        try { return Long.parseLong(a[i].trim().replace(",", "").replace("\"", "")); }
        catch (NumberFormatException e) { throw ApiException.bad((i + 1) + "번째 열 숫자 형식 오류: " + a[i]); }
    }
}
