import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Empty, Toast } from '../components/ui'
import { ApprovalPanel, Timeline, Comments } from '../components/DocPanels'
import { useSession } from '../session'
import { won, dt } from '../format'

export const ADJ_TYPE: Record<string, string> = { LOSS: '손실', SHORTAGE: '결손', INTERNAL_USE: '내부사용' }
const ADJ_HINT: Record<string, string> = {
  LOSS: '원인이 분명한 소멸: 유통기한, 파손, 품질 불량',
  SHORTAGE: '판매 외로 줄어든 재고: 실사 차이, 배송 부족, 시음, 샘플, 직원 음료',
  INTERNAL_USE: '매장 운영용 소모품 소진: 포장·위생·사무 용품 등',
}
const ST: Record<string, string> = { DRAFT: '작성 중', REQUESTED: '결재 중', APPROVED: '승인·반영', REJECTED: '반려', CANCELLED: '취소' }
const TONE: Record<string, any> = { DRAFT: 'neutral', REQUESTED: 'warn', APPROVED: 'ok', REJECTED: 'bad', CANCELLED: 'neutral' }

export function Adjustments() {
  const s = useSession()
  const nav = useNavigate()
  const list = useApi<any[]>(`/api/core/inventory/adjustments${s.storeId !== 'ALL' ? `?storeId=${s.storeId}` : ''}`, [s.storeId])
  return (
    <Page title="손실·결손·사용" sub="등록 → 매장관리자 → 점주 → 총괄 순차 결재 → 승인 시 재고 차감"
      actions={<button className="primary" onClick={() => nav('/adjustments/new')}>+ 등록</button>}>
      <ErrorBox error={list.error} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>번호</th><th>매장</th><th>유형</th><th>사유</th><th className="num">품목</th><th className="num">금액</th><th>상태</th><th>요청</th><th>승인</th></tr></thead>
          <tbody>{(list.data ?? []).map(a => (
            <tr key={a.id} className="clickable" onClick={() => nav(`/adjustments/${a.id}`)}>
              <td><code>{a.adj_no}</code>{a.source_type !== 'MANUAL' && <> <Badge tone="info">자동</Badge></>}</td>
              <td>{a.store_name}</td><td>{ADJ_TYPE[a.adj_type]}</td><td>{a.reasons}</td>
              <td className="num">{a.line_count}</td><td className="num">{won(a.total_amount)}</td>
              <td><Badge tone={TONE[a.status]}>{ST[a.status]}</Badge></td><td>{dt(a.requested_at)}</td><td>{dt(a.approved_at)}</td>
            </tr>))}</tbody>
        </table>
        {list.data?.length === 0 && <Empty>등록된 문서가 없습니다.</Empty>}
      </div>
    </Page>
  )
}

export function AdjustmentNew() {
  const s = useSession()
  const nav = useNavigate()
  const [storeId, setStoreId] = useState(s.storeId !== 'ALL' ? s.storeId : s.stores[0]?.id ?? '')
  const [type, setType] = useState('SHORTAGE')
  const reasons = useApi<any[]>('/api/core/inventory/reasons')
  const stock = useApi<any[]>(storeId ? `/api/core/inventory?storeId=${storeId}` : null, [storeId])
  const [lines, setLines] = useState<{ itemId: string; name: string; unit: string; qty: string; reasonCode: string; avg: number }[]>([])
  const [memo, setMemo] = useState('')
  const [q, setQ] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const myReasons = (reasons.data ?? []).filter(r => r.adj_type === type)
  const items = useMemo(() => (stock.data ?? []).filter(r => !q || r.item_name.includes(q)), [stock.data, q])
  const add = (r: any) => {
    if (lines.some(l => l.itemId === r.item_id)) return
    setLines([...lines, { itemId: r.item_id, name: r.item_name, unit: r.unit, qty: '1', reasonCode: myReasons[0]?.code ?? '', avg: Number(r.avg_cost) }])
  }
  const total = lines.reduce((t, l) => t + Math.round(Number(l.qty || 0) * l.avg), 0)
  const save = async (submit: boolean) => {
    setErr(null); setBusy(true)
    try {
      const r: any = await api('/api/core/inventory/adjustments', { body: { storeId, adjType: type, memo, submit, lines: lines.map(l => ({ itemId: l.itemId, qty: Number(l.qty), reasonCode: l.reasonCode })) } })
      nav(`/adjustments/${r.id}`)
    } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <Page title="손실·결손·사용 등록" sub="사진은 저장 후 상세 화면에서 첨부합니다">
      <div className="card form-inline">
        <div><label htmlFor="aj-store">매장</label><select id="aj-store" value={storeId} onChange={e => { setStoreId(e.target.value); setLines([]) }}>{s.stores.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</select></div>
        <div className="grow"><label>유형</label>
          <div className="seg">{Object.entries(ADJ_TYPE).map(([k, v]) => <button key={k} className={type === k ? 'on' : ''} onClick={() => { setType(k); setLines(ls => ls.map(l => ({ ...l, reasonCode: '' }))) }}>{v}</button>)}</div>
        </div>
      </div>
      <p className="muted small">{ADJ_HINT[type]}</p>
      <div className="split">
        <section className="card">
          <h2>매장 재고에서 고르기</h2>
          <input id="aj-q" aria-label="품목 검색" placeholder="품명 검색" value={q} onChange={e => setQ(e.target.value)} />
          <div className="item-list">
            {items.map(r => (
              <button key={r.item_id} className={`item-row ${lines.some(l => l.itemId === r.item_id) ? 'picked' : ''}`} onClick={() => add(r)}>
                <div className="item-info"><b>{r.item_name}</b><small className="muted">재고 {Number(r.qty)} {r.unit} · 평균 {won(r.avg_cost)}원</small></div><span>+</span>
              </button>))}
            {stock.data?.length === 0 && <div className="muted small">이 매장 재고가 없습니다.</div>}
          </div>
        </section>
        <section className="card cart" id="cart">
          <h2>{ADJ_TYPE[type]} 품목 ({lines.length})</h2>
          {lines.map((l, i) => (
            <div key={l.itemId} className="adj-line">
              <b>{l.name}</b>
              <div className="row3">
                <input aria-label="수량" inputMode="decimal" value={l.qty} onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, qty: e.target.value } : x))} />
                <select aria-label="사유" value={l.reasonCode} onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, reasonCode: e.target.value } : x))}>
                  <option value="">사유 선택</option>{myReasons.map(r => <option key={r.code} value={r.code}>{r.name}</option>)}
                </select>
                <button className="ghost small" aria-label="삭제" onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}>✕</button>
              </div>
              <small className="muted">{l.unit} · 예상 {won(Math.round(Number(l.qty || 0) * l.avg))}원</small>
            </div>
          ))}
          <dl className="totals"><dt className="strong">예상 금액 (공급가)</dt><dd className="strong">{won(total)}</dd></dl>
          <label htmlFor="aj-memo" className="small muted">메모</label>
          <textarea id="aj-memo" rows={2} value={memo} onChange={e => setMemo(e.target.value)} placeholder="예: 신메뉴 시음 행사" />
          {err && <div className="error-box">{err}</div>}
          <div className="act-buttons">
            <button disabled={busy || !lines.length} onClick={() => save(false)}>임시저장</button>
            <button className="primary" disabled={busy || !lines.length || lines.some(l => !l.reasonCode)} onClick={() => save(true)}>상신하기</button>
          </div>
        </section>
      </div>
    </Page>
  )
}

export function AdjustmentDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const d = useApi<any>(`/api/core/inventory/adjustments/${id}`)
  const [err, setErr] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const a = d.data
  if (!a) return <Page title="손실·결손"><ErrorBox error={d.error} /></Page>
  const run = async (p: string, ok: string) => { setErr(null); try { await api(`/api/core/inventory/adjustments/${id}/${p}`, { body: {} }); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) } }
  return (
    <Page title={a.adj_no} sub={<>{a.store_name} · {ADJ_TYPE[a.adj_type]} · <Badge tone={TONE[a.status]}>{ST[a.status]}</Badge>{a.source_type !== 'MANUAL' && <> <Badge tone="info">{a.source_type === 'LOGISTICS_RECEIPT' ? '배송 부족 자동' : '실사 자동'}</Badge></>}</>}
      actions={<>
        {a.status === 'DRAFT' && <button className="primary" onClick={() => run('submit', '상신했습니다')}>상신</button>}
        {['DRAFT', 'REJECTED'].includes(a.status) && <button onClick={() => run('cancel', '취소했습니다')}>취소</button>}
        <button className="ghost" onClick={() => nav('/adjustments')}>목록</button>
      </>}>
      <ErrorBox error={err} />
      <div className="detail">
        <div className="detail-main">
          <section className="card">
            <div className="kv">
              <div><small>작성</small><b>{dt(a.created_at)}</b></div><div><small>요청</small><b>{dt(a.requested_at)}</b></div>
              <div><small>승인</small><b>{dt(a.approved_at)}</b></div><div><small>재고 반영</small><b>{dt(a.completed_at)}</b></div>
            </div>
          </section>
          <section className="card table-wrap">
            <table className="tbl">
              <thead><tr><th>품목</th><th>사유</th><th className="num">수량</th><th className="num">단가</th><th className="num">금액</th></tr></thead>
              <tbody>{a.lines.map((l: any) => <tr key={l.id}><td>{l.item_name}</td><td>{l.reason_name}</td><td className="num">{Number(l.qty)} {l.unit}</td><td className="num">{won(l.unit_cost)}</td><td className="num">{won(l.amount)}</td></tr>)}</tbody>
            </table>
            <dl className="totals inline"><dt className="strong">합계 (공급가)</dt><dd className="strong">{won(a.total_amount)}</dd></dl>
            {a.memo && <p className="small"><b>메모</b> {a.memo}</p>}
          </section>
          <section className="card"><b>진행 이력</b><Timeline history={a.history} labels={ST} /></section>
        </div>
        <aside className="detail-side">
          <section className="card"><ApprovalPanel approval={a.approval} onDone={() => { setMsg('처리했습니다'); d.reload() }} /></section>
          <section className="card"><Comments docType="STOCK_ADJUSTMENT" docId={a.id} /></section>
        </aside>
      </div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
