#!/usr/bin/env python3
"""
Espresso 스모크 테스트 — 계정별 권한·핵심 업무 흐름을 API로 끝까지 실행해 확인합니다.
사용: python3 tests/smoke_test.py [기본 http://localhost:18080]
외부 라이브러리 없음. 데모 데이터에 새 문서(물류 요청·결손·비용·리포트)를 추가합니다. 여러 번 실행해도 됩니다.
"""
import json, sys, time, urllib.request, urllib.error, datetime

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080").rstrip("/")
PW = "demo1234"   # 데모 전용 공통 비밀번호
GN, HD, PG = ("22222222-0000-0000-0000-000000000001", "22222222-0000-0000-0000-000000000002", "22222222-0000-0000-0000-000000000003")
results, tokens = [], {}

def req(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None: r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            t = resp.read().decode()
            return resp.status, (json.loads(t) if t and t[0] in "[{" else t)
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try: return e.code, json.loads(t)
        except Exception: return e.code, t

def tok(user):
    if user not in tokens:
        s, b = req("POST", "/api/auth/login", body={"loginId": user, "password": PW})
        tokens[user] = b["accessToken"] if s == 200 else None
    return tokens[user]

def check(name, cond, detail=""):
    results.append((bool(cond), name, detail))
    print(("  PASS " if cond else "  FAIL ") + name + (f"  ({detail})" if detail and not cond else ""))

def section(t): print(f"\n■ {t}")

def approve_all(doc_type, doc_id, users):
    for u in users:
        s, inbox = req("GET", "/api/core/approvals?box=pending", tok(u))
        mine = [r for r in inbox if r["doc_type"] == doc_type and r["doc_id"] == doc_id] if s == 200 else []
        if not mine: return False, u
        s, _ = req("POST", f"/api/core/approvals/{mine[0]['id']}/approve", tok(u), {"comment": "스모크 테스트 승인"})
        if s != 200: return False, u
    return True, None

today = datetime.date.today()
last_period = (today.replace(day=1) - datetime.timedelta(days=1)).strftime("%Y%m")
cur_month = today.strftime("%Y-%m")

section("1. 로그인·매장 범위 (요구 9, 16, 26)")
expect = {"admin": 3, "logi": 3, "hr": 3, "owner1": 2, "owner2": 1, "mgr1": 1, "staff1": 1}
for u, n in expect.items():
    s, stores = req("GET", "/api/core/stores", tok(u))
    names = [x["name"] for x in stores] if s == 200 else []
    check(f"{u} 로그인 및 매장 {n}곳 이상", s == 200 and len(names) >= n, f"{s} {names}")
s, stores = req("GET", "/api/core/stores", tok("sv"))
check("sv(슈퍼바이저·비활성) 볼 수 있는 매장 없음", s == 200 and stores == [], str(stores)[:80])
s, stores = req("GET", "/api/core/stores", tok("investor1"))
check("investor1 운영 매장 범위 없음", s == 200 and stores == [])
check("잘못된 비밀번호 거부", req("POST", "/api/auth/login", body={"loginId": "admin", "password": "x"})[0] == 401)

section("2. 권한 차단")
check("staff1 계정 목록 접근 불가 (403)", req("GET", "/api/auth/users", tok("staff1"))[0] == 403)
check("owner2 → 강남점 접근 불가 (403)", req("GET", f"/api/core/stores/{GN}", tok("owner2"))[0] == 403)
check("logi → 투자 관리 접근 불가 (403)", req("GET", "/api/stl/investments", tok("logi"))[0] == 403)
check("owner1 → 투자 관리 접근 불가 (403)", req("GET", "/api/stl/investments", tok("owner1"))[0] == 403)
check("내부 API 외부 차단 (404)", req("GET", "/internal/stores")[0] == 404)
s, b = req("GET", f"/api/stl/settlement?storeId={GN}&period={last_period}", tok("mgr1"))
check("mgr1 결산: 결산액·로열티 숨김", s == 200 and b["stores"][0].get("settlement_amount") is None, str(b)[:120])
s, org = req("GET", "/api/auth/org", tok("mgr1"))
check("mgr1 조직도에 투자자 안 보임", s == 200 and all(a["role"] != "INVESTOR" for u in org["users"] for a in u["assignments"]))

section("3. 물류 요청 → 결재 → 발송 → 수령 (요구 1, 2, 6, 7, 8, 19, 22)")
s, items = req("GET", f"/api/core/catalog/items?storeId={GN}&q=CF-ETH", tok("mgr1"))
s2, milk = req("GET", f"/api/core/catalog/items?storeId={GN}&q=DA-MLK", tok("mgr1"))
s3, cats = req("GET", "/api/core/catalog/categories", tok("mgr1"))
pack = [c["id"] for c in cats if c["code"] == "PACKAGING"][0]
check("강남 원두 매장 할인 5% 적용 단가", items[0]["discount_type"] == "RATE")
s, r = req("POST", "/api/core/logistics", tok("mgr1"), {"storeId": GN, "title": "스모크 테스트", "submit": True, "lines": [
    {"sourceType": "HQ", "itemId": items[0]["id"], "qty": 2}, {"sourceType": "HQ", "itemId": milk[0]["id"], "qty": 12},
    {"sourceType": "EXTERNAL", "externalUrl": "https://example.com/item", "itemName": "외부 테스트 품목", "categoryId": pack, "qty": 1, "unitPrice": 11000, "priceInputMode": "INCLUSIVE", "payer": "HQ"}]})
check("mgr1 물류 요청 작성·상신", s == 200, str(r)[:120])
lr = r.get("id")
s, d = req("GET", f"/api/core/logistics/{lr}", tok("mgr1"))
lines = {l["item_name"]: l for l in d["lines"]}
check("면세 품목 세액 0", lines["흰우유 1L"]["vat_amount"] == 0)
check("외부 VAT 포함가 11,000 → 공급가 10,000 / 세액 1,000", lines["외부 테스트 품목"]["supply_amount"] == 10000 and lines["외부 테스트 품목"]["vat_amount"] == 1000)
steps = [(x["role"], x["status"]) for x in d["approval"]["steps"]]
check("결재선: 매장관리자 자동 통과 → 점주 대기", steps[0] == ("STORE_MGR", "SKIPPED") and steps[1] == ("OWNER", "PENDING"), str(steps))
ok, who = approve_all("LOGISTICS_REQUEST", lr, ["owner1", "logi", "admin"])
check("owner1 → logi → admin 순차 승인", ok, f"멈춘 단계: {who}")
ext = [l["id"] for l in d["lines"] if l["source_type"] == "EXTERNAL"]
check("logi 외부 구매 완료", req("POST", f"/api/core/logistics/{lr}/purchase", tok("logi"), {"lineIds": ext})[0] == 200)
check("staff1 은 발송 등록 불가 (403)", req("POST", f"/api/core/logistics/{lr}/ship", tok("staff1"), {"carrier": "x"})[0] == 403)
check("logi 발송 등록", req("POST", f"/api/core/logistics/{lr}/ship", tok("logi"), {"carrier": "본사 배송", "trackingNo": "SMOKE-1"})[0] == 200)
eth = [l["id"] for l in d["lines"] if l["item_name"].startswith("에티오피아")][0]
s, rc = req("POST", f"/api/core/logistics/{lr}/receive", tok("staff1"), {"lines": [{"lineId": eth, "receivedQty": 1}], "memo": "스모크 부분 수령"})
check("staff1 수령 검수 → 부족분 결손 초안 생성", s == 200 and rc.get("shortageAdjustmentId"), str(rc)[:100])
s, d = req("GET", f"/api/core/logistics/{lr}", tok("mgr1"))
hist = [h["to_status"] for h in d["history"]]
check("요청·승인·발송·완료 시각 기록", all(d.get(k) for k in ["requested_at", "approved_at", "shipped_at", "completed_at"]) and "PARTIALLY_RECEIVED" in hist, str(hist))

section("4. 결손 처리 (요구 10)")
s, inv = req("GET", f"/api/core/inventory?storeId={GN}", tok("staff1"))
eth_item = [x for x in inv if x["item_name"].startswith("에티오피아")][0]
before = float(eth_item["qty"])
s, a = req("POST", "/api/core/inventory/adjustments", tok("staff1"), {"storeId": GN, "adjType": "SHORTAGE", "memo": "스모크 시음", "submit": True,
    "lines": [{"itemId": eth_item["item_id"], "qty": 0.1, "reasonCode": "TASTING"}]})
check("staff1 시음 결손 상신", s == 200, str(a)[:100])
check("사유-유형 불일치 거부 (손실에 시음)", req("POST", "/api/core/inventory/adjustments", tok("staff1"), {"storeId": GN, "adjType": "LOSS",
    "lines": [{"itemId": eth_item["item_id"], "qty": 1, "reasonCode": "TASTING"}]})[0] == 400)
ok, who = approve_all("STOCK_ADJUSTMENT", a["id"], ["mgr1", "owner1", "admin"])
check("mgr1 → owner1 → admin 순차 승인", ok, f"멈춘 단계: {who}")
s, inv = req("GET", f"/api/core/inventory?storeId={GN}", tok("staff1"))
after = float([x for x in inv if x["item_id"] == eth_item["item_id"]][0]["qty"])
check("승인 후 재고 차감 0.1", abs(before - after - 0.1) < 1e-6, f"{before} → {after}")

section("5. 비용·결산·정산서 (요구 3, 11, 14)")
s, e = req("POST", "/api/stl/expenses", tok("mgr1"), {"storeId": GN, "expenseKind": "UTILITY", "accountCode": "814", "vendorName": "통신사(스모크)",
    "amount": 55000, "amountMode": "INCLUSIVE", "submit": True})
check("mgr1 통신비 상신", s == 200, str(e)[:100])
ok, who = approve_all("EXPENSE", e.get("id"), ["owner1", "admin"])
check("비용 owner1 → admin 승인", ok, f"멈춘 단계: {who}")
s, b = req("GET", f"/api/stl/settlement?storeId={GN}&period={today.strftime('%Y%m')}", tok("owner1"))
st = b["stores"][0] if s == 200 else {}
if today.day == 1:   # 매월 1일엔 이번 달 매출이 아직 없음 → 공과금만 확인
    st = {**st, "net_sales": st.get("net_sales") or 1}
check("이번 달 결산: 순매출·공과금 반영", st.get("net_sales", 0) > 0 and st.get("utility", 0) >= 50000, str({k: st.get(k) for k in ['net_sales', 'utility']}))
s, b = req("GET", f"/api/stl/royalty-policies?storeId={HD}", tok("admin"))
check("홍대 로열티 정책: 2% + 정액, VAT 포함 기준", s == 200 and any(p["type"] == "RATE_PLUS_FIXED" and p["base"] == "NET_SALES_INCL_VAT" for p in b))
s, sts = req("GET", "/api/stl/statements", tok("owner1"))
check("owner1 본사 정산서 조회", s == 200)

section("6. 급여 (요구 25)")
def ensure_payroll(store, publish):
    """지난달 급여가 없으면 hr 이 계산(새 DB·CI 대비). publish=True 면 상신 → 점주·총괄 승인 → 명세서 발행"""
    s, runs = req("GET", f"/api/core/payroll/runs?storeId={store}", tok("hr"))
    if s == 200 and runs: return
    s, r = req("POST", "/api/core/payroll/runs", tok("hr"), {"storeId": store, "period": last_period})
    if s != 200 or not publish: return
    req("POST", f"/api/core/payroll/runs/{r['id']}/submit", tok("hr"), {})
    approve_all("PAYROLL_RUN", r["id"], ["owner1", "admin"])
    req("POST", f"/api/core/payroll/runs/{r['id']}/publish", tok("hr"), {})
ensure_payroll(GN, True); ensure_payroll(HD, False)
s, runs = req("GET", f"/api/core/payroll/runs?storeId={HD}", tok("owner1"))
s2, run = req("GET", f"/api/core/payroll/runs/{runs[0]['id']}", tok("owner1")) if runs else (0, {})
check("홍대(열람 꺼짐) 점주는 급여 총액만", s2 == 200 and run.get("detailVisible") is False and run.get("payslips") == [], str(run.get("detailVisible")))
s, runs = req("GET", f"/api/core/payroll/runs?storeId={GN}", tok("owner1"))
s2, run = req("GET", f"/api/core/payroll/runs/{runs[0]['id']}", tok("owner1")) if runs else (0, {})
check("강남(열람 켜짐) 점주는 개인별 급여", s2 == 200 and len(run.get("payslips", [])) > 0)
s, me = req("GET", "/api/core/hr/me", tok("staff1"))
check("staff1 발행된 급여명세서 조회", s == 200 and len(me["payslips"]) > 0)
if me.get("payslips"):
    check("staff2 는 다른 직원 명세서 못 봄", req("GET", f"/api/core/payroll/payslips/{run['payslips'][0]['id']}", tok("staff2"))[0] == 403 if run.get("payslips") else True)

section("7. 리포트·비교·투자 (요구 4, 5, 15, 17)")
s, rp = req("POST", "/api/core/reports", tok("owner1"), {"storeId": GN, "period": last_period})
check("owner1 리포트 발행", s == 200, str(rp)[:100])
expect_sec = {"admin": {"SETTLEMENT", "LABOR_DETAIL"}, "logi": {"LOGISTICS", "STOCK"}, "hr": {"LABOR_TOTAL", "LABOR_DETAIL"}, "staff1": {"OWN_PAYSLIP"}}
deny_sec = {"logi": {"PNL", "SETTLEMENT"}, "hr": {"PNL", "LOGISTICS"}, "staff1": {"PNL", "SETTLEMENT", "LABOR_DETAIL"}, "mgr1": {"SETTLEMENT", "ROYALTY", "LABOR_DETAIL"}}
for u in ["admin", "logi", "hr", "staff1", "mgr1"]:
    s, v = req("GET", f"/api/core/reports/{rp['id']}", tok(u))
    secs = set(v.get("sections", {}).keys()) if s == 200 else set()
    check(f"{u} 리포트 섹션 범위", s == 200 and expect_sec.get(u, set()) <= secs and not (deny_sec.get(u, set()) & secs), str(sorted(secs)))
check("investor1 리포트 접근 불가", req("GET", f"/api/core/reports/{rp['id']}", tok("investor1"))[0] == 403)
s, cmp = req("GET", f"/api/stl/compare?period={last_period}", tok("admin"))
check("admin 매장 비교 (3곳 이상)", s == 200 and len(cmp) >= 3)
s, cmp = req("GET", f"/api/core/compare?period={last_period}", tok("logi"))
check("logi 비교: 물류 지표만 (급여 없음)", s == 200 and all("payroll_gross" not in x for x in cmp))
s, mine = req("GET", "/api/stl/investor/me", tok("investor1"))
check("investor1 공개 금액(강남 30%)만 조회", s == 200 and len(mine) == 1 and mine[0]["mode"] == "RATIO")

section("8. 식기·포장 구분 (요구 28 개정: 음료/디저트/식기·포장/관리, 식기 파손=손실, 고가 식기=비품)")
s, divs = req("GET", "/api/core/catalog/divisions", tok("mgr1"))
check("물류 구분 4개 (식기·포장 포함)", s == 200 and [d["code"] for d in divs] == ["BEVERAGE", "DESSERT", "TABLEWARE", "MANAGEMENT"], str(divs))
s, cats = req("GET", "/api/core/catalog/categories", tok("mgr1"))
tw_cats = {c["code"] for c in cats if c["division_code"] == "TABLEWARE"}
check("식기·포장 카테고리: 식기·포장·일회용품", tw_cats == {"TABLEWARE", "PACKAGING", "DISPOSABLE"}, str(tw_cats))
def sku(q): return req("GET", f"/api/core/catalog/items?storeId={GN}&q={q}", tok("mgr1"))[1][0]
mug, plate = sku("TW-MUG-350"), sku("TW-PLT-PRM")
check("기준 단가(10만원) 이상 식기는 비품, 이하 식기는 재고", plate["is_asset"] and not mug["is_asset"])
s, r = req("POST", "/api/core/logistics", tok("mgr1"), {"storeId": GN, "title": "스모크 식기", "submit": True, "lines": [
    {"sourceType": "HQ", "itemId": mug["id"], "qty": 2}, {"sourceType": "HQ", "itemId": plate["id"], "qty": 1}]})
tw_lr = r.get("id")
ok, who = approve_all("LOGISTICS_REQUEST", tw_lr, ["owner1", "logi", "admin"])
req("POST", f"/api/core/logistics/{tw_lr}/ship", tok("logi"), {"carrier": "본사 배송"})
check("식기 물류 요청 → 승인 → 수령", ok and req("POST", f"/api/core/logistics/{tw_lr}/receive", tok("staff1"), {"lines": []})[0] == 200, f"멈춘 단계: {who}")
s, a = req("POST", "/api/core/inventory/adjustments", tok("staff1"), {"storeId": GN, "adjType": "LOSS", "memo": "스모크 식기 파손", "submit": True,
    "lines": [{"itemId": mug["id"], "qty": 1, "reasonCode": "TABLEWARE_BROKEN"}]})
ok, who = approve_all("STOCK_ADJUSTMENT", a.get("id"), ["mgr1", "owner1", "admin"]) if s == 200 else (False, "상신 실패")
check("식기 파손 = 손실(식기 파손 사유) 결재 완료", ok, f"{who} {str(a)[:80]}")
assets = []
for _ in range(10):   # 결산 반영은 Outbox(3초 주기) 경유
    s, assets = req("GET", f"/api/stl/assets?storeId={GN}", tok("owner1"))
    assets = [x for x in assets if x["name"] == plate["name"] and not x["disposed_date"] and x["division_code"] == "TABLEWARE"] if s == 200 else []
    if assets: break
    time.sleep(2)
check("고가 식기 입고 → 비품 자산 등록 (식기·포장 구분)", bool(assets))
if assets:
    check("mgr1 은 비품 폐기 불가 (403)", req("POST", f"/api/stl/assets/{assets[0]['id']}/dispose", tok("mgr1"), {"reason": "파손"})[0] == 403)
    s, dp = req("POST", f"/api/stl/assets/{assets[0]['id']}/dispose", tok("owner1"), {"reason": "스모크 파손"})
    check("owner1 고가 식기 파손 → 비품 폐기(처분손실)", s == 200 and dp.get("bookValue") == plate["base_price"] - dp.get("accumulated", 0), str(dp)[:80])
twd = {}
for _ in range(10):   # 손실 승인 이벤트도 Outbox 경유
    s, sm = req("GET", f"/api/stl/settlement?storeId={GN}&period={today.strftime('%Y%m')}", tok("owner1"))
    twd = (sm.get("stores") or [{}])[0].get("by_division", {}).get("TABLEWARE", {}) if s == 200 else {}
    if twd.get("loss", 0) > 0: break
    time.sleep(2)
check("결산 구분별에 식기·포장 (손실 포함)", twd.get("loss", 0) > 0, str(twd))

section("9. 게이트웨이")
check("웹 화면 응답", req("GET", "/login")[0] == 200)

total = len(results); fails = [r for r in results if not r[0]]
print(f"\n결과: {total - len(fails)}/{total} 통과")
for f in fails: print("  실패:", f[1], f[2])
sys.exit(1 if fails else 0)
