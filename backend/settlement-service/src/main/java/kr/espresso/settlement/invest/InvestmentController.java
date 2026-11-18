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
    private final JdbcClient db;
    private final CoreClient core;

    public InvestmentController(JdbcClient db, CoreClient core) { this.db = db; this.core = core; }

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

    @GetMapping("/api/stl/investments")
    public Map<String, Object> overview() {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        audit(u.id(), "INVESTMENT_VIEW", null, "{}");
        List<Map<String, Object>> stores = new ArrayList<>();
        for (var st : core.allStores()) {
            UUID id = UUID.fromString(st.get("id").toString());
            var items = db.sql("select * from stl.investment_item where store_id = ? order by invested_on, category").param(id).query().listOfRows();
            long total = items.stream().mapToLong(i -> ((Number) i.get("amount")).longValue()).sum();
            Map<String, Long> byCat = new LinkedHashMap<>();
            items.forEach(i -> byCat.merge(CAT.get((String) i.get("category")), ((Number) i.get("amount")).longValue(), Long::sum));
            long cum = cumulativeSettlement(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store_id", id); m.put("store_name", st.get("name")); m.put("region_name", st.get("region_name")); m.put("open_date", st.get("open_date"));
            m.put("total", total); m.put("by_category", byCat); m.put("items", items);
            m.put("cumulative_settlement", cum); m.put("recovery_rate", total == 0 ? null : Math.round(cum * 1000.0 / total) / 10.0);
            m.put("disclosures", db.sql("select d.*, i.display_name, i.user_id from stl.investor_disclosure d join stl.investor i on i.id = d.investor_id where d.store_id = ? order by i.display_name").param(id).query().listOfRows());
            stores.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stores", stores);
        out.put("grand_total", stores.stream().mapToLong(s -> (Long) s.get("total")).sum());
        out.put("investors", db.sql("select * from stl.investor order by display_name").query().listOfRows());
        return out;
    }

    public record ItemReq(UUID storeId, String category, String description, Long amount, String investedOn) {}

    @PostMapping("/api/stl/investments")
    public Map<String, Object> add(@RequestBody ItemReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (!CAT.containsKey(r.category()) || r.amount() == null || r.amount() <= 0) throw ApiException.bad("구분과 금액을 확인하세요.");
        UUID id = UUID.randomUUID();
        db.sql("insert into stl.investment_item(id, store_id, category, description, amount, invested_on, created_by) values (?,?,?,?,?, cast(? as date), ?)")
                .params(id, r.storeId(), r.category(), Objects.requireNonNullElse(r.description(), CAT.get(r.category())), r.amount(),
                        Objects.requireNonNullElse(r.investedOn(), LocalDate.now().toString()), u.id()).update();
        audit(u.id(), "INVESTMENT_ADD", id, "{\"amount\":" + r.amount() + "}");
        return Map.of("id", id);
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
            m.put("store_name", names.get(store.toString())); m.put("mode", mode); m.put("amount", shown);
            if ("RATIO".equals(mode)) m.put("ratio", d.get("ratio"));
            if (Boolean.TRUE.equals(d.get("show_settlement"))) {
                long cum = cumulativeSettlement(store);
                long share = "TOTAL".equals(mode) ? cum : "RATIO".equals(mode) ? ((BigDecimal) d.get("ratio")).multiply(BigDecimal.valueOf(cum)).longValue()
                        : total == 0 ? 0 : Math.round(cum * (double) shown / total);
                m.put("cumulative_settlement", share);
                m.put("recovery_rate", shown == 0 ? null : Math.round(share * 1000.0 / shown) / 10.0);
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
}
