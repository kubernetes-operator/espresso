package kr.espresso.settlement.invest;

import kr.espresso.common.*;
import kr.espresso.settlement.support.CoreClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 요구 14·15: 투자금 = 매장 구성 총액. 원장은 총괄만, 투자자는 총괄이 공개한 금액(총액/지정액/비율)만
 * 조회 이력은 감사로그(INVESTMENT_VIEW)에 남김
 */
@RestController
public class InvestmentController {
    static final Map<String, String> CAT = Map.of("INTERIOR", "인테리어", "EQUIPMENT", "설비", "DEPOSIT", "임차보증금", "PREMIUM", "권리금", "INITIAL_STOCK", "초도 물품", "OTHER", "기타");
    static final Map<String, String> SOURCE = Map.of("INVESTOR", "투자자 출자", "HQ", "본사 지원");
    private final JdbcClient db;
    private final CoreClient core;
    private final kr.espresso.settlement.close.SettlementService stl;

    public InvestmentController(JdbcClient db, CoreClient core, kr.espresso.settlement.close.SettlementService stl) { this.db = db; this.core = core; this.stl = stl; }

    static boolean isStore(Map<String, Object> st) { return st.get("site_type") == null || "STORE".equals(st.get("site_type")); }

    void audit(UUID actor, String action, UUID target, String data) {
        db.sql("insert into stl.audit_log(actor_id, action, target_type, target_id, after_data) values (?,?, 'INVESTMENT', ?, cast(? as jsonb))").params(actor, action, target, data).update();
    }

    long cumulativeSettlement(UUID store) {
        Long v = db.sql("""
                select coalesce(sum(s.settlement_amount),0) from stl.settlement_summary s
                where s.store_id = ? and s.version = (select max(version) from stl.settlement_summary x where x.store_id = s.store_id and x.period = s.period)""")
                .param(store).query(Long.class).single();
        return v == null ? 0 : v;
    }

    /** 이익금 배분 비율 = 이 투자자의 출자액 / 매장 구성 총액(투자자 출자 + 본사 지원). 출자 없으면 null */
    BigDecimal shareOf(UUID investor, UUID store) {
        long total = db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ?").param(store).query(Long.class).single();
        long mine = db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ? and funding_source = 'INVESTOR' and investor_id = ?").params(store, investor).query(Long.class).single();
        if (total == 0 || mine == 0) return null;
        return BigDecimal.valueOf(mine).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /**
     * 투자자 지급액 결정 (인사 메뉴): 월 결산액 × 출자 비율 = 계산액 → 총괄·인사 관리자가 지급액 결정 → 지급 완료
     */
    @GetMapping("/api/stl/investor-payouts")
    public List<Map<String, Object>> payouts(@RequestParam String period) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.HR_MGR);
        Map<String, String> names = new HashMap<>();
        core.allStores().forEach(st -> names.put(st.get("id").toString(), (String) st.get("name")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var x : db.sql("""
                select distinct i.investor_id, v.display_name, i.store_id from stl.investment_item i join stl.investor v on v.id = i.investor_id
                where i.funding_source = 'INVESTOR' order by v.display_name""").query().listOfRows()) {
            UUID inv = (UUID) x.get("investor_id"), store = (UUID) x.get("store_id");
            BigDecimal share = shareOf(inv, store);
            if (share == null) continue;
            Map<String, Object> sm = stl.summary(store, period);
            long profit = ((Number) sm.get("settlement_amount")).longValue();
            long calc = Math.max(0, share.multiply(BigDecimal.valueOf(profit)).setScale(0, RoundingMode.FLOOR).longValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("investor_id", inv); m.put("investor_name", x.get("display_name")); m.put("store_id", store); m.put("store_name", names.get(store.toString()));
            m.put("period", period); m.put("profit", profit); m.put("share_ratio", share); m.put("calculated_amount", calc); m.put("closed", sm.get("closed"));
            m.put("investor_amount", db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ? and investor_id = ?").params(store, inv).query(Long.class).single());
            m.put("store_total", db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ?").param(store).query(Long.class).single());
            var p = db.sql("select id, profit, decided_amount, status, memo, decided_at, paid_at from stl.investor_payout where investor_id = ? and store_id = ? and period = ?")
                    .params(inv, store, period).query().listOfRows();
            m.put("payout", p.isEmpty() ? null : p.get(0));
            // 지급액 결정 뒤 수정 결산으로 결산액이 바뀐 경우 → 다시 검토 표시
            m.put("profit_changed", !p.isEmpty() && ((Number) p.get(0).get("profit")).longValue() != profit);
            out.add(m);
        }
        return out;
    }

    public record PayoutReq(UUID investorId, UUID storeId, String period, Long decidedAmount, String status, String memo) {}

    @PutMapping("/api/stl/investor-payouts")
    @Transactional
    public Map<String, Object> decide(@RequestBody PayoutReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN, Roles.HR_MGR);
        if (r.investorId() == null || r.storeId() == null || r.period() == null) throw ApiException.bad("투자자·매장·귀속월을 확인하세요.");
        if (r.decidedAmount() == null || r.decidedAmount() < 0) throw ApiException.bad("지급액(0원 이상)을 입력하세요.");
        String st = Objects.requireNonNullElse(r.status(), "DECIDED");
        if (!Set.of("DECIDED", "PAID").contains(st)) throw ApiException.bad("상태는 결정(DECIDED) / 지급 완료(PAID) 입니다.");
        BigDecimal share = shareOf(r.investorId(), r.storeId());
        if (share == null) throw ApiException.bad("이 매장에 이 투자자의 출자 항목이 없습니다.");
        long profit = ((Number) stl.summary(r.storeId(), r.period()).get("settlement_amount")).longValue();
        long calc = Math.max(0, share.multiply(BigDecimal.valueOf(profit)).setScale(0, RoundingMode.FLOOR).longValue());
        db.sql("""
                insert into stl.investor_payout(investor_id, store_id, period, profit, share_ratio, calculated_amount, decided_amount, status, memo, decided_by, paid_at)
                values (?,?,?,?,?,?,?,?,?,?, case when ? = 'PAID' then now() end)
                on conflict (investor_id, store_id, period) do update set profit = excluded.profit, share_ratio = excluded.share_ratio, calculated_amount = excluded.calculated_amount,
                  decided_amount = excluded.decided_amount, status = excluded.status, memo = excluded.memo, decided_by = excluded.decided_by, decided_at = now(),
                  paid_at = case when excluded.status = 'PAID' then coalesce(stl.investor_payout.paid_at, now()) else null end""")
                .params(r.investorId(), r.storeId(), r.period(), profit, share, calc, r.decidedAmount(), st, r.memo(), u.id(), st).update();
        audit(u.id(), "PAYOUT_" + st, r.investorId(), "{\"amount\":" + r.decidedAmount() + ",\"period\":\"" + r.period() + "\"}");
        return Map.of("ok", true, "calculated", calc);
    }

    @GetMapping("/api/stl/investments")
    public Map<String, Object> overview() {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        audit(u.id(), "INVESTMENT_VIEW", null, "{}");
        List<Map<String, Object>> stores = new ArrayList<>();
        for (var st : core.allStores()) {
            if (!isStore(st)) continue;   // 본사·베이커리는 투자 대상 매장이 아님
            UUID id = UUID.fromString(st.get("id").toString());
            var items = db.sql("select i.*, v.display_name investor_name from stl.investment_item i left join stl.investor v on v.id = i.investor_id where i.store_id = ? order by i.invested_on, i.category").param(id).query().listOfRows();
            long total = items.stream().mapToLong(i -> ((Number) i.get("amount")).longValue()).sum();
            long investorTotal = items.stream().filter(i -> "INVESTOR".equals(i.get("funding_source"))).mapToLong(i -> ((Number) i.get("amount")).longValue()).sum();
            Map<String, Long> byCat = new LinkedHashMap<>();
            items.forEach(i -> byCat.merge(CAT.get((String) i.get("category")), ((Number) i.get("amount")).longValue(), Long::sum));
            long cum = cumulativeSettlement(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store_id", id); m.put("store_name", st.get("name")); m.put("region_name", st.get("region_name")); m.put("open_date", st.get("open_date"));
            m.put("total", total); m.put("investor_total", investorTotal); m.put("hq_total", total - investorTotal);   // 매장 구성 총액 = 투자자 출자 + 본사 지원
            m.put("by_category", byCat); m.put("items", items);
            m.put("cumulative_settlement", cum); m.put("recovery_rate", total == 0 ? null : Math.round(cum * 1000.0 / total) / 10.0);
            m.put("disclosures", db.sql("select d.*, i.display_name, i.user_id from stl.investor_disclosure d join stl.investor i on i.id = d.investor_id where d.store_id = ? order by i.display_name").param(id).query().listOfRows());
            stores.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stores", stores);
        out.put("grand_total", stores.stream().mapToLong(s -> (Long) s.get("total")).sum());
        out.put("grand_investor", stores.stream().mapToLong(s -> (Long) s.get("investor_total")).sum());
        out.put("grand_hq", stores.stream().mapToLong(s -> (Long) s.get("hq_total")).sum());
        out.put("investors", db.sql("select * from stl.investor order by display_name").query().listOfRows());
        return out;
    }

    public record ItemReq(UUID storeId, String category, String description, Long amount, String investedOn, String fundingSource, UUID investorId) {}

    @PostMapping("/api/stl/investments")
    public Map<String, Object> add(@RequestBody ItemReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (!CAT.containsKey(r.category()) || r.amount() == null || r.amount() <= 0) throw ApiException.bad("구분과 금액을 확인하세요.");
        String src = Objects.requireNonNullElse(r.fundingSource(), "HQ");
        if (!SOURCE.containsKey(src)) throw ApiException.bad("자금 출처는 투자자 출자(INVESTOR) / 본사 지원(HQ) 입니다.");
        UUID id = UUID.randomUUID();
        if ("INVESTOR".equals(src) && r.investorId() == null) throw ApiException.bad("투자자 출자는 투자자를 고르세요.");
        db.sql("insert into stl.investment_item(id, store_id, category, description, amount, invested_on, funding_source, investor_id, created_by) values (?,?,?,?,?, cast(? as date), ?, ?, ?)")
                .params(id, r.storeId(), r.category(), Objects.requireNonNullElse(r.description(), CAT.get(r.category())), r.amount(),
                        Objects.requireNonNullElse(r.investedOn(), LocalDate.now().toString()), src, "INVESTOR".equals(src) ? r.investorId() : null, u.id()).update();
        audit(u.id(), "INVESTMENT_ADD", id, "{\"amount\":" + r.amount() + "}");
        return Map.of("id", id);
    }

    public record SourceReq(String fundingSource, UUID investorId) {}

    /** 자금 출처 변경 (투자자 출자 ↔ 본사 지원) */
    @PutMapping("/api/stl/investments/{id}")
    public Map<String, Object> setSource(@PathVariable UUID id, @RequestBody SourceReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (!SOURCE.containsKey(r.fundingSource())) throw ApiException.bad("자금 출처는 INVESTOR / HQ 입니다.");
        if ("INVESTOR".equals(r.fundingSource()) && r.investorId() == null) throw ApiException.bad("투자자 출자는 투자자를 고르세요.");
        db.sql("update stl.investment_item set funding_source = ?, investor_id = ? where id = ?").params(r.fundingSource(), "INVESTOR".equals(r.fundingSource()) ? r.investorId() : null, id).update();
        audit(u.id(), "INVESTMENT_SOURCE", id, "{\"source\":\"" + r.fundingSource() + "\"}");
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/stl/investments/{id}")
    public Map<String, Object> remove(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        db.sql("delete from stl.investment_item where id = ?").param(id).update();
        audit(u.id(), "INVESTMENT_DELETE", id, "{}");
        return Map.of("ok", true);
    }

    public record InvestorReq(UUID userId, String displayName) {}

    @PostMapping("/api/stl/investors")
    public Map<String, Object> addInvestor(@RequestBody InvestorReq r) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        UUID id = UUID.randomUUID();
        db.sql("insert into stl.investor(id, user_id, display_name) values (?,?,?)").params(id, r.userId(), r.displayName()).update();
        return Map.of("id", id);
    }

    public record DisclosureReq(UUID investorId, UUID storeId, String mode, Long fixedAmount, BigDecimal ratio, Boolean showSettlement) {}

    /** 투자자별 공개 설정 (요구 15): TOTAL 총액 / FIXED_AMOUNT 지정액 / RATIO 비율 */
    @PostMapping("/api/stl/investor-disclosures")
    @Transactional
    public Map<String, Object> disclose(@RequestBody DisclosureReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (!Set.of("TOTAL", "FIXED_AMOUNT", "RATIO").contains(r.mode())) throw ApiException.bad("공개 방식을 고르세요.");
        if ("RATIO".equals(r.mode()) && (r.ratio() == null || r.ratio().signum() <= 0 || r.ratio().compareTo(BigDecimal.ONE) > 0)) throw ApiException.bad("비율은 0~1 입니다.");
        db.sql("delete from stl.investor_disclosure where investor_id = ? and store_id = ?").params(r.investorId(), r.storeId()).update();
        UUID id = UUID.randomUUID();
        db.sql("insert into stl.investor_disclosure(id, investor_id, store_id, mode, fixed_amount, ratio, show_settlement, created_by) values (?,?,?,?,?,?,?,?)")
                .params(id, r.investorId(), r.storeId(), r.mode(), r.fixedAmount(), r.ratio(), Boolean.TRUE.equals(r.showSettlement()), u.id()).update();
        audit(u.id(), "DISCLOSURE_SET", id, "{\"mode\":\"" + r.mode() + "\"}");
        return Map.of("id", id);
    }

    @DeleteMapping("/api/stl/investor-disclosures/{id}")
    public Map<String, Object> undisclose(@PathVariable UUID id) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("delete from stl.investor_disclosure where id = ?").param(id).update();
        return Map.of("ok", true);
    }

    public record OnboardItem(String category, String description, Long amount, Boolean asset, Integer usefulLifeMonths) {}
    public record OnboardReq(UUID storeId, UUID actor, String royaltyType, String royaltyBase, BigDecimal royaltyRate, Long royaltyFixed, String openDate, List<OnboardItem> investments) {}

    /** 매장 생성 마법사(core) → 로열티 정책 + 투자 항목(설비는 자산 등록) */
    @PostMapping("/internal/onboarding")
    @Transactional
    public Map<String, Object> onboarding(@RequestBody OnboardReq r) {
        LocalDate d = r.openDate() == null ? LocalDate.now() : LocalDate.parse(r.openDate());
        if (r.royaltyType() != null)
            db.sql("insert into stl.royalty_policy(store_id, type, base, rate, fixed_amount, valid_from, created_by) values (?,?,?,?,?,?,?)")
                    .params(r.storeId(), r.royaltyType(), Objects.requireNonNullElse(r.royaltyBase(), "NET_SALES_EXCL_VAT"), r.royaltyRate(), r.royaltyFixed(),
                            d.withDayOfMonth(1), r.actor()).update();
        int n = 0;
        for (OnboardItem i : r.investments() == null ? List.<OnboardItem>of() : r.investments()) {
            if (i.amount() == null || i.amount() <= 0 || !CAT.containsKey(i.category())) continue;
            UUID asset = null;
            if (Boolean.TRUE.equals(i.asset())) {
                asset = UUID.randomUUID();
                db.sql("insert into stl.fixed_asset(id, store_id, name, category_code, acquired_date, cost, useful_life_months, source_type) values (?,?,?, 'EQUIPMENT', ?,?,?, 'ONBOARDING')")
                        .params(asset, r.storeId(), i.description(), d, i.amount(), Objects.requireNonNullElse(i.usefulLifeMonths(), 60)).update();
            }
            db.sql("insert into stl.investment_item(store_id, category, description, amount, invested_on, asset_id, created_by) values (?,?,?,?,?,?,?)")
                    .params(r.storeId(), i.category(), Objects.requireNonNullElse(i.description(), CAT.get(i.category())), i.amount(), d, asset, r.actor()).update();
            n++;
        }
        audit(r.actor(), "ONBOARDING", r.storeId(), "{\"investments\":" + n + "}");
        return Map.of("investments", n);
    }

    /** 투자자 본인 화면: 공개된 값만 */
    @GetMapping("/api/stl/investor/me")
    public List<Map<String, Object>> mine() {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.INVESTOR);
        var inv = db.sql("select id, display_name from stl.investor where user_id = ?").param(u.id()).query().listOfRows();
        if (inv.isEmpty()) return List.of();
        audit(u.id(), "INVESTOR_VIEW", (UUID) inv.get(0).get("id"), "{}");
        Map<String, String> names = new HashMap<>();
        core.allStores().forEach(s -> names.put(s.get("id").toString(), (String) s.get("name")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var d : db.sql("select * from stl.investor_disclosure where investor_id = ? and visible_from <= current_date and (visible_to is null or visible_to >= current_date)")
                .param(inv.get(0).get("id")).query().listOfRows()) {
            UUID store = (UUID) d.get("store_id");
            long total = db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ?").param(store).query(Long.class).single();
            String mode = (String) d.get("mode");
            long shown = switch (mode) {
                case "FIXED_AMOUNT" -> ((Number) d.get("fixed_amount")).longValue();
                case "RATIO" -> ((BigDecimal) d.get("ratio")).multiply(BigDecimal.valueOf(total)).setScale(0, RoundingMode.FLOOR).longValue();
                default -> total;
            };
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store_id", store);
            m.put("store_name", names.get(store.toString())); m.put("mode", mode); m.put("amount", shown);
            BigDecimal fundShare = shareOf((UUID) inv.get(0).get("id"), store);
            m.put("profit_share_ratio", fundShare);   // 이익금 배분 비율 = 내 출자 / (투자자 출자 + 본사 지원)
            m.put("payouts", db.sql("select period, decided_amount, status, paid_at from stl.investor_payout where investor_id = ? and store_id = ? order by period desc")
                    .params(inv.get(0).get("id"), store).query().listOfRows());
            if ("TOTAL".equals(mode)) {   // 총액 공개: 투자자 출자 / 본사 지원 구분
                long invAmt = db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ? and funding_source = 'INVESTOR'").param(store).query(Long.class).single();
                m.put("investor_total", invAmt); m.put("hq_total", total - invAmt);
            }
            if ("RATIO".equals(mode)) m.put("ratio", d.get("ratio"));
            if (Boolean.TRUE.equals(d.get("show_settlement"))) {
                long cum = cumulativeSettlement(store);
                long share = fundShare != null ? fundShare.multiply(BigDecimal.valueOf(cum)).longValue() : "TOTAL".equals(mode) ? cum : "RATIO".equals(mode) ? ((BigDecimal) d.get("ratio")).multiply(BigDecimal.valueOf(cum)).longValue()
                        : total == 0 ? 0 : Math.round(cum * (double) shown / total);
                m.put("cumulative_settlement", share);
                long base = shown;
                if (fundShare != null) {   // 출자 비율 배분이면 회수율 = 내 몫 / 내 출자액
                    base = db.sql("select coalesce(sum(amount),0) from stl.investment_item where store_id = ? and investor_id = ?").params(store, inv.get(0).get("id")).query(Long.class).single();
                    m.put("my_investment", base);
                }
                m.put("recovery_rate", base == 0 ? null : Math.round(share * 1000.0 / base) / 10.0);
                m.put("monthly", db.sql("""
                        select period, settlement_amount from stl.settlement_summary s where store_id = ?
                          and version = (select max(version) from stl.settlement_summary x where x.store_id = s.store_id and x.period = s.period) order by period""")
                        .param(store).query().listOfRows().stream().map(x -> {
                            long v = ((Number) x.get("settlement_amount")).longValue();
                            long sv = "TOTAL".equals(mode) ? v : "RATIO".equals(mode) ? ((BigDecimal) d.get("ratio")).multiply(BigDecimal.valueOf(v)).longValue() : total == 0 ? 0 : Math.round(v * (double) shown / total);
                            return Map.<String, Object>of("period", x.get("period"), "amount", sv);
                        }).toList());
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 투자자 분석: 투자 매장의 최근 12개월 월별 매출액·영업이익·결산액 (마감 전 달은 실시간 계산)
     * 결산액(내 몫)은 공개 설정에서 결산 공개를 켠 경우에만
     */
    @GetMapping("/api/stl/investor/me/trend")
    public List<Map<String, Object>> trend() {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.INVESTOR);
        var inv = db.sql("select id from stl.investor where user_id = ?").param(u.id()).query(UUID.class).optional();
        if (inv.isEmpty()) return List.of();
        Map<String, String> names = new HashMap<>();
        core.allStores().forEach(s -> names.put(s.get("id").toString(), (String) s.get("name")));
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var d : db.sql("select * from stl.investor_disclosure where investor_id = ? and visible_from <= current_date and (visible_to is null or visible_to >= current_date)")
                .param(inv.get()).query().listOfRows()) {
            UUID store = (UUID) d.get("store_id");
            boolean showStl = Boolean.TRUE.equals(d.get("show_settlement"));
            BigDecimal fund = shareOf(inv.get(), store);
            BigDecimal ratio = fund != null ? fund : "RATIO".equals(d.get("mode")) ? (BigDecimal) d.get("ratio") : BigDecimal.ONE;
            List<Map<String, Object>> months = new ArrayList<>();
            var fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMM");
            // 매출액: 일 매출(과거 실적 포함). 이익: 결산 분개가 있는 달만 (없으면 null → 선 끊김)
            Map<String, Long> sales = new HashMap<>();
            core.monthlySales(store, now.minusMonths(11).format(fmt), now.format(fmt)).forEach(x -> sales.put((String) x.get("period"), ((Number) x.get("net_sales")).longValue()));
            for (int i = 11; i >= 0; i--) {
                String p = now.minusMonths(i).format(fmt);
                Map<String, Object> s = stl.summary(store, p);
                boolean booked = ((Number) s.getOrDefault("net_sales", 0)).longValue() != 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("period", p); m.put("net_sales", sales.get(p)); m.put("operating_profit", booked ? s.get("operating_profit") : null);
                m.put("closed", s.get("closed"));
                if (showStl) m.put("settlement_share", booked ? ratio.multiply(BigDecimal.valueOf(((Number) s.get("settlement_amount")).longValue())).setScale(0, RoundingMode.FLOOR).longValue() : null);
                months.add(m);
            }
            out.add(Map.of("store_id", store, "store_name", names.getOrDefault(store.toString(), ""), "show_settlement", showStl, "months", months));
        }
        return out;
    }
}
