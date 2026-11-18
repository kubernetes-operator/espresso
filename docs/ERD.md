# Espresso ERD

DDL 원본: `db/auth`, `db/core`, `db/settlement` (PostgreSQL 16, Flyway 형식, 로컬 PostgreSQL 16에서 적용 검증 완료).
DB 간에는 FK 없이 ID 값으로만 참조합니다(점선 = DB 경계 너머 값 참조).

## 1. DB 구성

| DB (클러스터) | 스키마 | 소유 서비스 |
|---|---|---|
| `auth_db` | `auth` | auth-service |
| `core_db` | `common`, `store`, `catalog`, `approval`, `logistics`, `inventory`, `sales`, `hr`, `report`, `notify` | 각 서비스 (스키마 = 서비스 경계) |
| `settlement_db` | `stl` | settlement-service |

## 2. auth

```mermaid
erDiagram
  role ||--o{ role : parent
  app_user ||--o{ user_role_assignment : has
  role ||--o{ user_role_assignment : grants
  app_user ||--o{ refresh_token : ""
  app_user ||--o{ push_device : ""
  user_role_assignment {
    uuid user_id
    string role_code
    string scope_type "ALL|REGION|STORE"
    uuid scope_id "store/region (core_db)"
  }
  app_user {
    string login_id
    string vat_display_override "NULL=매장 기본"
  }
```

## 3. core — 매장·카탈로그·물류

```mermaid
erDiagram
  region ||--o{ store : contains
  store ||--o{ store_lease : ""
  store ||--|| store_onboarding : wizard
  division ||--o{ category : groups
  category ||--o{ item : ""
  item ||--o{ store_price : "매장별 단가·할인"
  store ||--o{ store_price : ""
  logistics_package ||--o{ logistics_package_item : ""
  request ||--o{ request_line : ""
  item |o--o{ request_line : "HQ 라인"
  request ||--o{ shipment : ""
  shipment ||--o{ shipment_line : ""
  request_line ||--o{ shipment_line : ""
  request_line {
    string source_type "HQ|EXTERNAL"
    text external_url
    string payer "STORE|HQ"
    string tax_type "TAXABLE|EXEMPT"
    bigint supply_amount
    bigint vat_amount "면세=0"
    numeric received_qty
  }
  request {
    string status
    timestamptz requested_at
    timestamptz approved_at
    timestamptz shipped_at
    timestamptz received_at
    timestamptz completed_at
  }
```

## 4. core — 결재·재고

```mermaid
erDiagram
  line_template ||--o{ line_template_step : ""
  line_template ||--o{ approval_request : matched
  approval_request ||--o{ approval_step : ""
  stock_adjustment ||--o{ stock_adjustment_line : ""
  reason_code ||--o{ stock_adjustment_line : ""
  stock_count ||--o{ stock_count_line : ""
  stock_count |o--o| stock_adjustment : "차이 → 결손"
  stock_balance ||--o{ stock_ledger : "store_id,item_id"
  approval_request {
    string doc_type
    uuid doc_id "logistics/adjustment/payroll/expense"
    bigint amount
  }
  approval_step {
    string approver_type "ROLE|USER"
    uuid_array assignee_ids
    string status
    timestamptz activated_at
    timestamptz acted_at
    text comment "반려 시 필수"
  }
  reason_code {
    string code "TASTING, STAFF_DRINK ..."
    string adj_type "LOSS|SHORTAGE|INTERNAL_USE"
  }
```

공통: `common.attachment`, `common.doc_comment`(메모), `common.status_history`(모든 상태 전이 시각) 은 `(doc_type, doc_id)` 로 모든 문서에 연결.

## 5. core — 매출·인사·리포트

```mermaid
erDiagram
  import_batch ||--o{ daily_sales : ""
  daily_sales ||--o{ daily_sales_division : "음료/디저트/식기·포장/관리"
  employee ||--o{ employment_contract : ""
  employee ||--o{ work_record : ""
  payroll_run ||--o{ payslip : ""
  employee ||--o{ payslip : ""
  payslip ||--o{ payslip_item : ""
  report_snapshot ||--o{ access_log : ""
```

## 6. settlement

```mermaid
erDiagram
  account_subject ||--o{ journal_line : ""
  journal_entry ||--o{ journal_line : ""
  account_subject ||--o{ expense : ""
  recurring_expense ||--o{ expense : generates
  royalty_policy }o--|| store_ref : "매장별"
  hq_statement ||--o{ hq_statement_line : ""
  hq_statement ||--o{ hq_statement_payment : "입금 확인"
  tax_invoice |o--o{ hq_statement : ""
  tax_invoice |o--o{ expense : ""
  fixed_asset |o--o{ investment_item : ""
  investor ||--o{ investor_disclosure : ""
  period_close }o--|| store_ref : ""
  settlement_summary }o--|| store_ref : ""
  royalty_policy {
    string type "RATE|FIXED|RATE_PLUS_FIXED|TIERED"
    string base "기본 NET_SALES_EXCL_VAT"
  }
  hq_statement {
    string status "ISSUED→PARTIALLY_PAID→PAID/OVERDUE"
    bigint paid_amount
  }
  investor_disclosure {
    string mode "TOTAL|FIXED_AMOUNT|RATIO"
  }
```

## 7. 이벤트 → 분개 매핑

| 이벤트 | 차변 | 대변 |
|---|---|---|
| DailySalesClosed | 현금/외상매출금(카드) | 상품매출-구분별(401 음료·402 디저트·406 식기·포장·403 기타), 부가세예수금 |
| LogisticsReceived (매장 결제) | 상품(재고) 또는 비품, 부가세대급금 | 외상매입금/보통예금 |
| — 비품 판단 | 설비 카테고리, 또는 카테고리 비품 기준 단가 이상(고가 식기) → 비품(206) + fixed_asset(구분 기록) | |
| LogisticsReceived (본사 결제) | 상품(재고) 또는 비품, 부가세대급금 | **미지급금(본사)** |
| StockAdjustmentApproved — 손실 | 재고자산폐기손실 | 상품 |
| — 결손(실사·배송) | 재고자산감모손실 | 상품 |
| — 결손(시음·샘플·직원음료) | 시음·샘플·직원음료 | 상품 |
| — 내부사용 | 소모품비 | 상품 |
| — 손실(식기 파손) | 재고자산폐기손실 (구분: 식기·포장) | 상품 |
| StockConsumed (실사 판매 소진) | 음료·디저트: 매출원가 / 식기·포장·관리: 소모품비 | 상품 |
| 비품 폐기 (결산 › 설비·비품) | 감가상각누계액, 유형자산처분손실(970) | 비품(206) |
| PayrollConfirmed | 급여·잡급·제수당, 복리후생비, 퇴직급여 | 예수금, 미지급금, 퇴직급여충당부채 |
| ExpenseApproved | 지급임차료/수도광열비/… , 부가세대급금 | 미지급금 또는 미지급금(본사) |
| 월 마감 로열티 | 지급로열티 | 미지급금(본사) |
| 정산서 입금 확인 | 미지급금(본사) | 보통예금 |
