import { useEffect, useRef } from 'react'
import mermaid from 'mermaid'
import { Page } from '../components/ui'

const ARCH = `flowchart LR
  subgraph C["사용자 화면"]
    W["웹 React<br/>노트북·태블릿 우선"]
    M["모바일<br/>하단 탭"]
  end
  GW["nginx 게이트웨이<br/>(K8s: Ingress+gateway)"]
  W --> GW
  M --> GW
  A["auth-service<br/>계정·등급·조직"]
  CO["core-service<br/>매장·카탈로그·결재·물류<br/>재고·매출·인사급여·리포트"]
  S["settlement-service<br/>분개·결산·로열티·정산서·투자"]
  GW --> A
  GW --> CO
  GW --> S
  CO -- "이벤트 (Outbox)" --> S
  DA[("auth_db")]
  DC[("core_db")]
  DS[("settlement_db")]
  A --> DA
  CO --> DC
  S --> DS
  subgraph PG["PostgreSQL 2중화"]
    P["primary"] -- "streaming" --> SB["standby"]
  end
  DA -.- P
  DC -.- P
  DS -.- P`

const ORG = `flowchart TB
  HQ["총괄 관리자"]
  HQ --> L["물류 관리자"]
  HQ --> H["인적 관리자"]
  HQ -.-> SV["슈퍼바이저 (비활성)"]
  HQ --> O["점주"]
  O --> SM["매장 관리자"]
  SM --> ST["일반 사원"]
  INV["투자자 (계층 밖)"]
  classDef hq fill:#e3edf4,stroke:#2f5d7c,color:#1d3a4f
  classDef st fill:#e6f0df,stroke:#4f7a3a,color:#2c4620
  classDef ex fill:#eee6f5,stroke:#7a5a9a,color:#47335c
  class L,H,SV hq
  class O,SM,ST st
  class INV ex`

const LOGI = `stateDiagram-v2
  direction LR
  [*] --> 작성중
  작성중 --> 요청: 상신
  요청 --> 매장승인: 매장관리자·점주
  매장승인 --> 물류승인: 물류관리자
  물류승인 --> 준비중: 본사 물류
  물류승인 --> 구매완료: 외부 구매
  구매완료 --> 준비중
  준비중 --> 발송: 송장
  발송 --> 수령: 매장 검수
  수령 --> 완료
  요청 --> 반려
  완료 --> [*]`

const ADJ = `sequenceDiagram
  participant 등록 as 사원·매장관리자
  participant M as 매장관리자
  participant O as 점주
  participant H as 총괄
  participant S as 결산
  등록->>M: 결손 등록 (시음·직원음료·파손…, 사진)
  M->>O: 승인
  O->>H: 승인
  H-->>등록: 최종 승인 → 재고 차감
  H-->>S: 감모·폐기 분개`

function Diagram({ id, code }: { id: string; code: string }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches
    mermaid.initialize({ startOnLoad: false, theme: dark ? 'dark' : 'neutral', securityLevel: 'strict', fontFamily: 'inherit' })
    mermaid.render(`mm-${id}`, code).then(r => { if (ref.current) ref.current.innerHTML = r.svg })
      .catch(e => { if (ref.current) ref.current.textContent = '다이어그램을 그리지 못했습니다: ' + e.message })
  }, [id, code])
  return <div className="diagram" ref={ref} />
}

export function Blueprint() {
  return (
    <Page title="설계도" sub="설계서 v0.3 요약 · 전체 문서는 저장소 docs/ 폴더">
      <div className="grid2">
        <section className="card"><h2>시스템 구성</h2><Diagram id="arch" code={ARCH} /></section>
        <section className="card"><h2>등급 구조</h2><Diagram id="org" code={ORG} /></section>
        <section className="card"><h2>물류 요청 흐름</h2><Diagram id="logi" code={LOGI} /></section>
        <section className="card"><h2>결손·손실 승인</h2><Diagram id="adj" code={ADJ} /></section>
      </div>
      <section className="card">
        <h2>기본 결재선</h2>
        <table className="tbl"><thead><tr><th>문서</th><th>결재 순서</th></tr></thead><tbody>
          <tr><td>물류 요청</td><td>매장관리자 → 점주 → 물류관리자 → 총괄</td></tr>
          <tr><td>손실·결손·내부사용</td><td>매장관리자 → 점주 → 총괄</td></tr>
          <tr><td>급여 확정</td><td>인적관리자 → 점주 → 총괄</td></tr>
          <tr><td>비용</td><td>매장관리자 → 점주 → 총괄</td></tr>
        </tbody></table>
        <p className="muted small">요청자 본인 단계는 자동 통과. 총괄이 [설정 › 결재선]에서 매장·금액별로 줄이거나 승인자를 지정할 수 있습니다.</p>
      </section>
      <section className="card">
        <h2>결산 공식</h2>
        <table className="tbl"><tbody>
          <tr><td>순매출</td><td>총매출 − 할인 − 환불 (VAT 제외)</td></tr>
          <tr><td>매출원가</td><td>기초재고 + 매입 − 기말재고</td></tr>
          <tr><td>판관비</td><td>인건비 + 임대 + 공과금 + 내부사용 + 운영비 + 손실·결손 + 감가상각</td></tr>
          <tr><td><b>결산액</b></td><td><b>순매출 − 매출원가 − 판관비 − 로열티 ± 영업외</b></td></tr>
        </tbody></table>
      </section>
    </Page>
  )
}
