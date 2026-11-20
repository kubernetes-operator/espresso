package kr.espresso.core.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.espresso.common.*;
import kr.espresso.core.logistics.LogisticsService;
import kr.espresso.core.support.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.*;

/**
 * 요구 27: 총괄의 매장 생성 마법사 (8단계, 단계별 임시저장 → 완료 시 일괄 반영)
 *  1 기본정보 · 2 부가세·결산·로열티 · 3 임대 · 4 설비·투자 · 5 초도 물류·단가 · 6 인원 · 7 결재선 · 8 검토
 */
@RestController
@RequestMapping("/api/core/onboarding")
public class OnboardingController {
    private final JdbcClient db;
    private final ObjectMapper om;
    private final AuthClient auth;
    private final RestClient stl;
    private final LogisticsService logistics;
    private final DocTrail trail;

    public OnboardingController(JdbcClient db, ObjectMapper om, AuthClient auth, @Value("${app.settlement-url}") String stlUrl, LogisticsService logistics, DocTrail trail) {
        this.db = db; this.om = om; this.auth = auth; this.stl = RestClient.create(stlUrl); this.logistics = logistics; this.trail = trail;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        return db.sql("""
                select s.id, s.code, s.name, s.status, o.current_step, o.started_at, o.completed_at from store.store_onboarding o
                join store.store s on s.id = o.store_id order by o.started_at desc""").query().listOfRows();
    }

    @GetMapping("/{storeId}")
    public Map<String, Object> get(@PathVariable UUID storeId) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        var r = db.sql("select o.*, cast(o.step_data as text) data, s.code, s.name, s.status from store.store_onboarding o join store.store s on s.id = o.store_id where o.store_id = ?")
                .param(storeId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("step_data", parse((String) r.get("data"))); m.remove("data");
        return m;
    }

    public record StartReq(String code, String name, UUID regionId, String businessType, String siteType) {}

    @PostMapping
    public Map<String, Object> start(@RequestBody StartReq r) {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        if (r.code() == null || r.code().isBlank() || r.name() == null || r.name().isBlank()) throw ApiException.bad("매장 코드와 이름을 입력하세요.");
        if (db.sql("select count(*) from store.store where code = ?").param(r.code().trim()).query(Integer.class).single() > 0) throw ApiException.conflict("이미 있는 매장 코드입니다.");
        UUID id = UUID.randomUUID();
        String site = "BAKERY".equals(r.siteType()) ? "BAKERY" : "STORE";   // 매장 / 베이커리 (본사는 1개 고정)
        db.sql("insert into store.store(id, code, name, region_id, status, business_type, site_type, created_by) values (?,?,?,?, 'ONBOARDING', ?, ?, ?)")
                .params(id, r.code().trim(), r.name().trim(), r.regionId(), "BAKERY".equals(site) ? "DIRECT" : Objects.requireNonNullElse(r.businessType(), "FRANCHISE"), site, u.id()).update();
        String init = json(Map.of("1", Map.of("code", r.code(), "name", r.name(), "regionId", Objects.toString(r.regionId(), ""), "businessType", Objects.requireNonNullElse(r.businessType(), "FRANCHISE"))));
        db.sql("insert into store.store_onboarding(store_id, current_step, step_data, started_by) values (?, 2, cast(? as jsonb), ?)").params(id, init, u.id()).update();
        return Map.of("storeId", id);
    }

    /** 단계 임시저장 */
    @PutMapping("/{storeId}/steps/{step}")
    public Map<String, Object> save(@PathVariable UUID storeId, @PathVariable int step, @RequestBody Map<String, Object> data) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        db.sql("update store.store_onboarding set step_data = step_data || jsonb_build_object(cast(? as text), cast(? as jsonb)), current_step = greatest(current_step, ?) where store_id = ? and completed_at is null")
                .params(String.valueOf(step), json(data), Math.min(step + 1, 8), storeId).update();
        return Map.of("ok", true);
    }

    @DeleteMapping("/{storeId}")
    public Map<String, Object> discard(@PathVariable UUID storeId) {
        CurrentUser.get().require(Roles.HQ_ADMIN);
        var st = db.sql("select status from store.store where id = ?").param(storeId).query(String.class).single();
        if (!"ONBOARDING".equals(st)) throw ApiException.conflict("이미 개설된 매장은 삭제할 수 없습니다.");
        db.sql("delete from store.store_onboarding where store_id = ?").param(storeId).update();
        db.sql("delete from catalog.store_price where store_id = ?").param(storeId).update();
        db.sql("delete from store.store_lease where store_id = ?").param(storeId).update();
        db.sql("delete from store.store where id = ?").param(storeId).update();
        return Map.of("ok", true);
    }

    /** 완료: 매장·임대·단가·인원·결재선·로열티·투자 반영 + 초도 물류 요청 자동 생성 → 매장 OPEN */
    @PostMapping("/{storeId}/complete")
    public Map<String, Object> complete(@PathVariable UUID storeId) throws Exception {
        CurrentUser u = CurrentUser.get();
        u.require(Roles.HQ_ADMIN);
        var o = get(storeId);
        if (o.get("completed_at") != null) throw ApiException.conflict("이미 완료된 마법사입니다.");
        JsonNode d = om.valueToTree(o.get("step_data"));
        JsonNode s1 = d.path("1"), s2 = d.path("2"), s3 = d.path("3"), s4 = d.path("4"), s5 = d.path("5"), s6 = d.path("6"), s7 = d.path("7");
        List<String> done = new ArrayList<>();
        // 1·2 기본정보·부가세
        db.sql("""
                update store.store set name = coalesce(cast(? as varchar), name), biz_reg_no = ?, biz_name = ?, address = ?, phone = ?, open_date = cast(? as date),
                  vat_display_mode = coalesce(cast(? as varchar), vat_display_mode), owner_payroll_detail_visible = ?,
                  settings = settings || jsonb_build_object('fivePlus', ?), updated_at = now() where id = ?""")
                .params(txt(s1, "name"), txt(s1, "bizRegNo"), txt(s1, "bizName"), txt(s1, "address"), txt(s1, "phone"), txt(s1, "openDate"),
                        txt(s2, "vatDisplayMode"), s2.path("ownerPayrollVisible").asBoolean(false), s2.path("fivePlus").asBoolean(true), storeId).update();
        done.add("기본정보·부가세 설정");
        // 3 임대
        if (s3.path("monthlyRent").asLong(0) > 0 || s3.path("deposit").asLong(0) > 0) {
            db.sql("insert into store.store_lease(store_id, lessor_name, deposit, monthly_rent, maintenance_fee, payment_day, start_date, end_date) values (?,?,?,?,?,?, cast(? as date), cast(? as date))")
                    .params(storeId, txt(s3, "lessorName"), s3.path("deposit").asLong(0), s3.path("monthlyRent").asLong(0), s3.path("maintenanceFee").asLong(0),
                            s3.path("paymentDay").asInt(25), Objects.requireNonNullElse(txt(s3, "startDate"), Objects.requireNonNullElse(txt(s1, "openDate"), java.time.LocalDate.now().toString())), txt(s3, "endDate")).update();
            done.add("임대 계약");
        }
        // 5 매장 단가·할인
        int prices = 0;
        for (JsonNode p : s5.path("prices")) {
            if (p.path("itemId").asText("").isEmpty()) continue;
            db.sql("insert into catalog.store_price(store_id, item_id, unit_price, discount_type, discount_value, valid_from, created_by) values (?,?,?,?,?, current_date, ?)")
                    .params(storeId, UUID.fromString(p.path("itemId").asText()), p.hasNonNull("unitPrice") ? p.path("unitPrice").asLong() : null,
                            txt(p, "discountType"), p.hasNonNull("discountValue") ? new BigDecimal(p.path("discountValue").asText()) : null, u.id()).update();
            prices++;
        }
        if (prices > 0) done.add("매장 단가·할인 " + prices + "건");
        // 6 인원 배정 (auth)
        int people = 0;
        for (String[] k : new String[][]{{"ownerIds", "OWNER"}, {"managerIds", "STORE_MGR"}, {"staffIds", "STAFF"}, {"logisticsIds", "LOGISTICS_MGR"}, {"hrIds", "HR_MGR"}})
            for (JsonNode uid : s6.path(k[0])) {
                if (uid.asText("").isBlank()) continue;
                auth.grant(UUID.fromString(uid.asText()), k[1], "STORE", storeId); people++;
            }
        if (people > 0) done.add("인원 배정 " + people + "명");
        // 7 결재선 (매장 전용)
        for (JsonNode line : s7.path("lines")) {
            if (!line.path("steps").isArray() || line.path("steps").isEmpty()) continue;
            UUID tid = UUID.randomUUID();
            db.sql("insert into approval.line_template(id, doc_type, name, store_id, priority, created_by) values (?,?,?,?, 20, ?)")
                    .params(tid, line.path("docType").asText(), "매장 생성 시 지정", storeId, u.id()).update();
            int seq = 1;
            for (JsonNode st : line.path("steps"))
                db.sql("insert into approval.line_template_step(template_id, seq, approver_type, role_code, user_id) values (?,?,?,?,?)")
                        .params(tid, seq++, st.path("approverType").asText("ROLE"), txt(st, "roleCode"),
                                st.hasNonNull("userId") && !st.path("userId").asText().isEmpty() ? UUID.fromString(st.path("userId").asText()) : null).update();
            done.add("결재선(" + line.path("docType").asText() + ")");
        }
        // 2·4 로열티·투자 (settlement)
        Map<String, Object> ob = new HashMap<>();
        ob.put("storeId", storeId); ob.put("actor", u.id()); ob.put("openDate", txt(s1, "openDate"));
        ob.put("royaltyType", txt(s2, "royaltyType")); ob.put("royaltyBase", txt(s2, "royaltyBase"));
        ob.put("royaltyRate", s2.hasNonNull("royaltyRate") && !s2.path("royaltyRate").asText().isEmpty() ? new BigDecimal(s2.path("royaltyRate").asText()) : null);
        ob.put("royaltyFixed", s2.hasNonNull("royaltyFixed") && !s2.path("royaltyFixed").asText().isEmpty() ? s2.path("royaltyFixed").asLong() : null);
        List<Map<String, Object>> inv = new ArrayList<>();
        for (JsonNode i : s4.path("items")) inv.add(Map.of("category", i.path("category").asText(), "description", i.path("description").asText(""),
                "amount", i.path("amount").asLong(0), "asset", i.path("asset").asBoolean(false)));
        if (s3.path("deposit").asLong(0) > 0) inv.add(Map.of("category", "DEPOSIT", "description", "임차보증금", "amount", s3.path("deposit").asLong(), "asset", false));
        ob.put("investments", inv);
        stl.post().uri("/internal/onboarding").body(ob).retrieve().toBodilessEntity();
        done.add("로열티·투자 " + inv.size() + "건");
        db.sql("update store.store set status = 'OPEN' where id = ?").param(storeId).update();
        // 5 초도 물류 요청 (총괄 요청 → 결재 자동 통과 → 물류 처리 보드)
        UUID initial = null;
        List<LogisticsService.LineReq> lines = new ArrayList<>();
        for (JsonNode l : s5.path("initialItems"))
            if (l.path("qty").asDouble(0) > 0 && !l.path("itemId").asText("").isBlank()) lines.add(new LogisticsService.LineReq("HQ", UUID.fromString(l.path("itemId").asText()), new BigDecimal(l.path("qty").asText()),
                    null, null, null, null, null, null, null, null, null, null, null));
        if (!lines.isEmpty()) {
            initial = logistics.create(new LogisticsService.SaveReq(storeId, "초도 물류 (매장 생성)", txt(s1, "openDate"), "매장 생성 마법사에서 자동 생성", lines, true), u);
            db.sql("update logistics.request set is_initial_order = true where id = ?").param(initial).update();
            done.add("초도 물류 요청 " + lines.size() + "개 품목");
        }
        db.sql("update store.store_onboarding set completed_at = now(), current_step = 8 where store_id = ?").param(storeId).update();
        trail.log("STORE", storeId, "ONBOARDING", "OPEN", u.id(), String.join(", ", done));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", storeId); out.put("applied", done); out.put("initialRequestId", initial);
        return out;
    }

    static String txt(JsonNode n, String f) { return n.hasNonNull(f) && !n.path(f).asText().isBlank() ? n.path(f).asText() : null; }
    String json(Object o) { try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    Map<String, Object> parse(String s) { try { return om.readValue(s, Map.class); } catch (Exception e) { return Map.of(); } }
}
