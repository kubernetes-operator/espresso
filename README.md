# Cafe de Lyon (Espresso) — 커피숍 물류·회계 통합 시스템

물류 요청·발송(음료/디저트/식기·포장/관리), 재고·손실·결손, 비품(설비·고가 식기), 매출·비용·결산·로열티·본사 정산, 급여, 등급별 리포트, 매장 비교, 투자 관리를
계정 등급(총괄·물류·인사·점주·매장관리자·사원·투자자)과 매장 범위에 따라 나눠 쓰는 시스템입니다.

## 실행

```bash
deploy/build.sh                                   # 백엔드 JAR + 웹 빌드 (Java·Node 없이 Docker 로 빌드)
cd deploy/compose && docker compose up -d --build # PostgreSQL primary+standby, auth/core/settlement, nginx
# 웹: http://<서버>:18080   (포트 변경: WEB_PORT=8081 docker compose up -d)
```

- 데모 계정 비밀번호는 모두 `demo1234` (데모 전용, `DEMO_SEED=false` 로 끄기). 계정·시나리오: [docs/TEST-SCENARIOS.md](docs/TEST-SCENARIOS.md)
- 점검: `python3 tests/smoke_test.py` (계정별 권한·업무 흐름·식기·포장·1차 점검 반영 115항목), `bash tests/check_replication.sh` (DB 2중화)
- CI: GitHub Actions `.github/workflows/smoke-test.yml` — PR·main·develop·feature/** 푸시마다 새 DB로 전체 구성을 띄워 위 두 점검을 실행 (`tests/wait_ready.py`로 기동·시드 대기, 실패 시 서비스 로그 출력)
- 계정 서비스는 기동할 때 토큰 서명 키를 메모리에 새로 만듭니다 → 재시작하면 다시 로그인. 운영에서는 KMS/Secret 키로 교체.

## 구성

| 경로 | 내용 |
|---|---|
| `docs/DESIGN.md` · `ERD.md` · `SCREENS.md` | 설계서, DB 관계도, 화면 설계 |
| `docs/blueprint.html` | 설계도(조직 구성도·시스템 구성·흐름) — 앱 안 [설계도] 메뉴에도 있음 |
| `db/{auth,core,settlement}` | PostgreSQL 16 DDL (Flyway 마이그레이션, 서비스가 기동 시 적용) |
| `backend/` | Spring Boot 3 / Java 21, Gradle 멀티모듈 — `common`, `auth-service`, `core-service`(모듈형: 매장·카탈로그·결재·물류·재고·매출·인사급여·리포트), `settlement-service`(분개·결산·로열티·정산서·세무·투자) |
| `web/` | React 18 + Vite + TypeScript — 노트북·태블릿 우선, 모바일 하단 탭 |
| `deploy/compose` | 소형 구성(WAS 모드) docker-compose, nginx 게이트웨이 |
| `PLAN.md` | 구현 단계·진행 로그 |

## 서비스 간 연동

- 인증: auth-service 가 RS256 JWT 발급, core·settlement 는 JWKS 로 검증. 매장 범위는 core 가 등급 배정(ALL/REGION/STORE)으로 계산.
- 결산 데이터: core 의 업무 이벤트(물류 입고, 손실·결손, 판매 소진, 일 매출 마감, 급여 확정)를 Transactional Outbox → settlement 로 전달(3초 주기, 순서 보장, 멱등 수신) → 복식부기 분개.
- 결재: core 의 범용 결재 엔진(물류·결손·급여·비용). 비용 문서는 settlement 에 있고 결재만 core 에서 처리.
- 내부 API(`/internal/**`)는 nginx 에서 외부 차단. 운영 전환 시 mTLS 또는 client-credentials 로 보호 필요.

## 설계 대비 현재 상태

| 항목 | 상태 |
|---|---|
| 모바일 앱 (React Native) | 미구현 — 반응형 웹의 하단 탭 화면으로 대체 확인 |
| Kubernetes (Helm·CloudNativePG·Kafka) | 미구현 — 현재 docker-compose 소형 구성. DB 복제는 비동기(운영 목표는 동기) |
| DB 접근 | JPA 대신 Spring JdbcClient(SQL 직접) |
| PDF | 브라우저 인쇄 저장 |
| 전자세금계산서 | 대기열 + 승인번호 수기 입력 (발행 대행사 선정 후 연동) |
| 레시피 기반 재고 차감, POS 연동, 매장 간 이동 | 4차 범위 |
| 급여 요율·간이세액표 | 데모 값/추정 — 운영 전 고시값 등록 필요 |

## Kubernetes 배포 (cafedelyon)

| 항목 | 값 |
|---|---|
| 주소 | https://lyon.studiobasa.com (공용 Gateway `default/gateway` 에 HTTPRoute 연결) |
| 네임스페이스 | `cafedelyon` |
| 이미지 | `registry.local.cloud:5000/cafedelyon/{auth,core,settlement,web}:<커밋 7자리>` — `deploy/images.sh [--push]` |
| 차트 | `deploy/helm/cafedelyon` (PostgreSQL primary+standby StatefulSet, 서비스 4개, 업로드 PVC, 업로드 크기 정책) |

```bash
# 1) DB 자격 증명 Secret — 운영자가 직접 생성 (차트·저장소에 값 없음)
kubectl create namespace cafedelyon
kubectl -n cafedelyon create secret generic cafedelyon-db \
  --from-literal=postgres-password='<직접 정한 값>' --from-literal=replication-password='<직접 정한 값>'
# 2) 이미지
deploy/images.sh --push
# 3) 설치·업그레이드
helm upgrade --install cafedelyon deploy/helm/cafedelyon -n cafedelyon --set image.tag=$(git rev-parse --short=7 HEAD)
```
- TLS: 공용 인증서(`default/letsencrypt-issuer-public-cert`)의 dnsNames 에 도메인 추가 필요(공용 리소스 변경).
- DNS: Cloudflare 에 `lyon.studiobasa.com` 레코드 필요.
- 서명 키(메모리)·Outbox 순서 때문에 auth·core 는 1개 인스턴스. 데모 데이터는 `demo.seed=false` 로 끔.
