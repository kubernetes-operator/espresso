import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox } from '../components/ui'
import { dt } from '../format'

export function StockCount() {
  const { id } = useParams()
  const nav = useNavigate()
  const c = useApi<any>(`/api/core/inventory/counts/${id}`)
  const [qty, setQty] = useState<Record<string, string>>({})
  const [q, setQ] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [result, setResult] = useState<any>(null)
  const [mode, setMode] = useState<'USAGE' | 'SHORTAGE'>('USAGE')
  useEffect(() => { if (c.data) setQty(Object.fromEntries(c.data.lines.map((l: any) => [l.item_id, String(Number(l.counted_qty))]))) }, [c.data])
  if (!c.data) return <Page title="재고 실사"><ErrorBox error={c.error} /></Page>
  const done = c.data.status !== 'IN_PROGRESS'
  const lines = c.data.lines.filter((l: any) => !q || l.item_name.includes(q) || (l.barcode ?? '') === q)
  const diffs = c.data.lines.filter((l: any) => Number(qty[l.item_id]) !== Number(l.system_qty))
  const submit = async () => {
    setErr(null)
    try { setResult(await api(`/api/core/inventory/counts/${id}/submit`, { body: { shortageMode: mode, lines: c.data.lines.map((l: any) => ({ itemId: l.item_id, countedQty: Number(qty[l.item_id] || 0) })) } })); c.reload() }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="재고 실사" sub={<>시작 {dt(c.data.started_at)} · <Badge tone={done ? 'ok' : 'warn'}>{done ? '제출됨' : '진행 중'}</Badge></>}
      actions={!done && <button className="primary" onClick={submit}>실사 제출 ({diffs.length}건 차이)</button>}>
      <ErrorBox error={err || c.error} />
      {!done && <div className="card"><b>부족분 처리</b>
        <div className="seg" style={{ marginTop: 8 }}>
          <button className={mode === 'USAGE' ? 'on' : ''} onClick={() => setMode('USAGE')}>판매 소진 (음료·디저트 원가 / 식기·포장·관리 소모품비)</button>
          <button className={mode === 'SHORTAGE' ? 'on' : ''} onClick={() => setMode('SHORTAGE')}>결손 (실사 차이, 결재)</button>
        </div>
        <p className="muted small">월말 실사로 원두·우유처럼 판매에 쓰인 양을 확정할 때는 '판매 소진'을, 원인 모를 부족은 '결손'을 고르세요.</p></div>}
      {result && result.mode === 'USAGE' && <div className="note warn">부족 {result.shortItems}건을 판매 소진(원가·소모품비)으로 반영했습니다.{result.surplusItems > 0 && ` 초과 ${result.surplusItems}건은 재고에 더했습니다.`}</div>}
      {result && result.mode !== 'USAGE' && <div className="note warn">부족 {result.shortItems}건은 결손(실사 차이) 초안으로 만들었습니다.{result.surplusItems > 0 && ` 초과 ${result.surplusItems}건은 바로 재고에 반영했습니다.`}
        {result.shortageAdjustmentId && <> <button className="small" onClick={() => nav(`/adjustments/${result.shortageAdjustmentId}`)}>결손 문서 열기 →</button></>}</div>}
      <input id="cnt-q" aria-label="품명 또는 바코드" placeholder="품명 검색 또는 바코드 입력" value={q} onChange={e => setQ(e.target.value)} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>품목</th><th>카테고리</th><th className="num">장부</th><th className="num">실제</th><th className="num">차이</th></tr></thead>
          <tbody>{lines.map((l: any) => {
            const d = Number(qty[l.item_id] ?? 0) - Number(l.system_qty)
            return (
              <tr key={l.item_id}><td>{l.item_name}</td><td><small>{l.category_name}</small></td><td className="num">{Number(l.system_qty)} {l.unit}</td>
                <td className="num">{done ? Number(l.counted_qty) : <input aria-label={`${l.item_name} 실제 수량`} className={`mini ${d ? 'warn-input' : ''}`} inputMode="decimal" value={qty[l.item_id] ?? ''} onChange={e => setQty({ ...qty, [l.item_id]: e.target.value })} />}</td>
                <td className="num">{d ? <b className={d < 0 ? 'bad-text' : 'ok-text'}>{d > 0 ? '+' : ''}{d}</b> : '—'}</td></tr>)
          })}</tbody>
        </table>
      </div>
      
    </Page>
  )
}
