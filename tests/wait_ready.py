#!/usr/bin/env python3
"""
서비스 기동 + 데모 데이터 시드 완료까지 대기 (CI·새 환경용).
사용: python3 tests/wait_ready.py [http://localhost:18080] [제한 초, 기본 300]
조건: admin 로그인 → 매장 3곳 이상(core 시드) → 지난달 강남 매출 존재(매출 시드) → 로열티 정책 조회(settlement 기동)
      → 데모 매출 이벤트가 결산에 모두 반영(Outbox 전달 완료: 가장 마지막 매출일의 달 순매출 > 0)
      → 마지막 데모 시드(재고 소비 이력) 완료
"""
import json, sys, time, datetime, urllib.request, urllib.error

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080").rstrip("/")
LIMIT = int(sys.argv[2]) if len(sys.argv) > 2 else 300
GN = "22222222-0000-0000-0000-000000000001"

def call(path, token=None, body=None):
    r = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body else None, method="POST" if body else "GET")
    if body: r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r, timeout=10) as resp:
        return json.loads(resp.read().decode() or "null")

last_month = (datetime.date.today().replace(day=1) - datetime.timedelta(days=1)).strftime("%Y-%m")
start, step = time.time(), "로그인"
while time.time() - start < LIMIT:
    try:
        step = "로그인"
        tok = call("/api/auth/login", body={"loginId": "admin", "password": "demo1234"})["accessToken"]   # 데모 전용 계정
        step = "매장 시드"
        assert len(call("/api/core/stores", tok)) >= 3
        step = "매출 시드"
        assert len(call(f"/api/core/sales?month={last_month}&storeId={GN}", tok)) > 0
        step = "결산 서비스"
        call(f"/api/stl/royalty-policies?storeId={GN}", tok)
        step = "결산 반영(Outbox)"
        yday = datetime.date.today() - datetime.timedelta(days=1)   # 데모 매출은 어제까지
        stl = call(f"/api/stl/settlement?storeId={GN}&period={yday.strftime('%Y%m')}", tok)
        assert stl["stores"][0]["net_sales"] > 0
        step = "마지막 데모 시드(재고 소비 이력)"   # core 의 데모 시드는 순서대로 실행 → 마지막 것이 끝났으면 급여·근무표 시드도 끝난 것
        cs = call(f"/api/core/reports/consumption?unit=month&count=3&storeId={GN}", tok)
        assert any(r["bean"]["used_kg"] > 0 for r in cs["stores"][0]["rows"])
        print(f"준비 완료 ({int(time.time() - start)}초)")
        sys.exit(0)
    except (urllib.error.URLError, AssertionError, KeyError, TypeError, ConnectionError, TimeoutError, json.JSONDecodeError):
        time.sleep(3)
print(f"시간 초과: '{step}' 단계에서 {LIMIT}초 안에 준비되지 않았습니다.")
sys.exit(1)
