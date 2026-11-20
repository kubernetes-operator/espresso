import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Modal, Toast } from '../components/ui'
import { ApprovalPanel, Timeline, Comments } from '../components/DocPanels'
import { useSession } from '../session'
import { won, dt, day, DIVISIONS } from '../format'
import { LR_STATUS, LR_TONE } from './logisticsShared'

export function LogisticsDetail() {
  const { id } = useParams()
  const s = useSession()
  const nav = useNavigate()
  const d = useApi<any>(`/api/core/logistics/${id}`)
  const [err, setErr] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [shipOpen, setShipOpen] = useState(false)
  const [recvOpen, setRecvOpen] = useState(false)
  const [adjLine, setAdjLine] = useState<any>(null)
  const [dueOpen, setDueOpen] = useState(false)
  const r = d.data
  if (!r) return <Page title="물류 요청"><ErrorBox error={d.error} />{!d.error && <div className="muted">불러오는 중…</div>}</Page>

  const vatMode = (s.user?.vatOverride as any) || s.stores.find(x => x.id === r.store_id)?.vat_display_mode || 'EXCLUSIVE'
  const inc = vatMode === 'INCLUSIVE'
  const isLogi = s.has('LOGISTICS_MGR', 'HQ_ADMIN')
  const isStore = s.has('OWNER', 'STORE_MGR', 'STAFF', 'HQ_ADMIN')
  const extOpen = r.lines.filter((l: any) => l.source_type === 'EXTERNAL' && l.line_status === 'OPEN')
  const run = async (path: string, body: any, ok: string) => {
    setErr(null)
    try { await api(`/api/core/logistics/${id}/${path}`, { body }); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) }
  }

  return (
    <Page title={`${r.request_no}`} sub={<>{r.store_name} · {r.title || '제목 없음'} · <Badge tone={LR_TONE[r.status]}>{LR_STATUS[r.status]}</Badge>{r.kind === 'RESEND' && <> <Badge tone="accent">재발송(무상)</Badge></>}</>}
      actions={<>
        {isLogi && !['COMPLETED', 'CANCELLED', 'REJECTED'].includes(r.status) && <button onClick={() => setDueOpen(true)}>처리 기한</button>}
        {r.status === 'DRAFT' && <button className="primary" onClick={() => run('submit', {}, '상신했습니다')}>상신</button>}
        {['DRAFT', 'REJECTED'].includes(r.status) && <button onClick={() => run('cancel', {}, '취소했습니다')}>요청 취소</button>}
        {isLogi && ['LOGISTICS_APPROVED', 'PURCHASED'].includes(r.status) && !extOpen.length && <button onClick={() => run('prepare', {}, '출고 준비로 바꿨습니다')}>출고 준비</button>}
        {isLogi && ['LOGISTICS_APPROVED', 'PURCHASED', 'PREPARING'].includes(r.status) && <button className="primary" disabled={extOpen.length > 0} onClick={() => setShipOpen(true)}>발송 등록</button>}
        {r.status === 'SHIPPED' && <button onClick={() => run('deliver', {}, '도착 처리했습니다')}>도착 확인</button>}
        {isStore && ['SHIPPED', 'DELIVERED'].includes(r.status) && <button className="primary" onClick={() => setRecvOpen(true)}>수령 검수</button>}
        <button className="ghost" onClick={() => nav('/logistics')}>목록</button>
      </>}>
      <ErrorBox error={err} />
      <div className="detail">
        <div className="detail-main">
          <section className="card">
            <div className="kv">
              <div><small>요청</small><b>{dt(r.requested_at)}</b></div>
              <div><small>최종 승인</small><b>{dt(r.approved_at)}</b></div>
              <div><small>발송</small><b>{dt(r.shipped_at)}</b></div>
              <div><small>완료</small><b>{dt(r.completed_at)}</b></div>
              <div><small>요청 기한</small><b>{day(r.desired_date)}</b></div>
              <div><small>처리 기한</small><b className={r.due_date && !r.completed_at && r.due_date < new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10) ? 'overdue' : ''}>{day(r.due_date)}</b></div>
            </div>
          </section>
          <section className="card table-wrap">
            <table className="tbl">
              <thead><tr><th>#</th><th>품목</th><th>구분</th><th className="num">수량</th><th className="num">단가</th><th className="num">할인</th><th className="num">공급가</th><th className="num">세액</th><th>상태</th><th></th></tr></thead>
              <tbody>
                {r.lines.map((l: any) => (
                  <tr key={l.id}>
                    <td>{l.line_no}</td>
                    <td>
                      {l.source_type === 'EXTERNAL' ? <>
                        <Badge tone="accent">외부</Badge> {l.item_name}
                        <div className="small"><a href={l.external_url} target="_blank" rel="noreferrer">{l.vendor_name || '상품 링크'} ↗</a> · {l.payer === 'HQ' ? '본사 결제' : '매장 결제'} · 입력 {l.price_input_mode === 'INCLUSIVE' ? 'VAT 포함가' : 'VAT 별도가'}{l.shipping_fee > 0 && ` · 배송비 ${won(l.shipping_fee)}`}</div>
                      </> : l.item_name}
                    </td>
                    <td><small>{DIVISIONS[l.division_code]} · {l.category_name}</small></td>
                    <td className="num">{Number(l.qty)}{l.received_qty != null && Number(l.received_qty) !== Number(l.qty) && <div className="small warn-text">수령 {Number(l.received_qty)}</div>}</td>
                    <td className="num">{won(l.list_unit_price)}</td>
                    <td className="num">{l.discount_amount ? `-${won(l.discount_amount)}` : ''}</td>
                    <td className="num">{won(l.supply_amount)}</td>
                    <td className="num">{l.tax_type === 'EXEMPT' ? '0 (면세)' : won(l.vat_amount)}</td>
                    <td><Badge tone={l.line_status === 'SHORT' ? 'warn' : 'neutral'}>{({ OPEN: '대기', PURCHASED: '구매 완료', SHIPPED: '발송', RECEIVED: '수령', SHORT: '부족', CANCELLED: '취소' } as any)[l.line_status]}</Badge></td>
                    <td>
                      {isLogi && l.source_type === 'EXTERNAL' && l.line_status === 'OPEN' && r.status === 'LOGISTICS_APPROVED' &&
                        <button className="small" onClick={() => run('purchase', { lineIds: [l.id] }, '구매 완료로 바꿨습니다')}>구매 완료</button>}
                      {isLogi && ['REQUESTED', 'STORE_APPROVED'].includes(r.status) && <button className="ghost small" onClick={() => setAdjLine(l)}>단가 조정</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <dl className="totals inline">
              <dt>과세 공급가</dt><dd>{won(r.taxable_supply)}</dd><dt>면세</dt><dd>{won(r.exempt_supply)}</dd>
              <dt>세액</dt><dd>{won(r.vat_amount)}</dd><dt>배송비</dt><dd>{won(r.shipping_fee)}</dd>
              <dt className="strong">합계 (VAT {inc ? '포함' : '별도'})</dt><dd className="strong">{won(inc ? r.total_amount : r.taxable_supply + r.exempt_supply + r.shipping_fee)}</dd>
            </dl>
            {r.memo && <p className="small"><b>요청 메모</b> {r.memo}</p>}
            {r.related?.filter((x: any) => x.id !== r.id).length > 0 && <p className="small"><b>관련 요청</b> {r.related.filter((x: any) => x.id !== r.id).map((x: any) =>
              <button key={x.id} className="ghost small" onClick={() => nav(`/logistics/${x.id}`)}>{x.request_no} {x.kind === 'RESEND' ? '(재발송)' : '(원 요청)'} · {LR_STATUS[x.status]}</button>)}</p>}
            {r.lines.some((l: any) => l.problem_type) && <div className="note warn small">수령 문제: {r.lines.filter((l: any) => l.problem_type).map((l: any) =>
              `${l.item_name} ${Number(l.problem_qty)}개 ${PROBLEM[l.problem_type]} → ${l.resolution === 'RESEND' ? '재발송' : '손실·결손 처리'}`).join(' / ')}</div>}
            {r.shipments.length > 0 && <p className="small"><b>송장</b> {r.shipments.map((x: any) => `${x.carrier ?? ''} ${x.tracking_no ?? ''} (${dt(x.shipped_at)})`).join(', ')}</p>}
          </section>
          <section className="card"><b>진행 이력</b><Timeline history={r.history} labels={LR_STATUS} /></section>
        </div>
        <aside className="detail-side">
          <section className="card"><ApprovalPanel approval={r.approval} onDone={() => { setMsg('처리했습니다'); d.reload() }} /></section>
          <section className="card"><Comments docType="LOGISTICS_REQUEST" docId={r.id} /></section>
        </aside>
      </div>

      {shipOpen && <ShipModal onClose={() => setShipOpen(false)} onSubmit={async (b: any) => { await run('ship', b, '발송 등록했습니다'); setShipOpen(false) }} />}
      {recvOpen && <ReceiveModal lines={r.lines} onClose={() => setRecvOpen(false)}
        onSubmit={async (b: any) => {
          setErr(null)
          try {
            const res: any = await api(`/api/core/logistics/${id}/receive`, { body: b })
            setMsg(['검수 완료', res.adjustmentIds?.length ? `손실·결손 초안 ${res.adjustmentIds.length}건` : '', res.resendRequestId ? '재발송 요청 생성' : ''].filter(Boolean).join(' · '))
            setRecvOpen(false); d.reload()
          } catch (e: any) { setErr(e.message); setRecvOpen(false) }
        }} />}
      {adjLine && <AdjustModal line={adjLine} onClose={() => setAdjLine(null)}
        onSubmit={async (b: any) => {
          setErr(null)
          try { await api(`/api/core/logistics/${id}/lines/${adjLine.id}`, { method: 'PUT', body: b }); setMsg('단가·수량을 조정했습니다'); setAdjLine(null); d.reload() }
          catch (e: any) { setErr(e.message); setAdjLine(null) }
        }} />}
      {dueOpen && <DueModal value={r.due_date ?? r.desired_date ?? ''} onClose={() => setDueOpen(false)}
        onSubmit={async (v: string) => { setErr(null); try { await api(`/api/core/logistics/${id}/due-date`, { method: 'PUT', body: { dueDate: v || null } }); setMsg('처리 기한을 정했습니다'); setDueOpen(false); d.reload() } catch (e: any) { setErr(e.message); setDueOpen(false) } }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

const PROBLEM: Record<string, string> = { SHORT: '부족(미도착)', DAMAGED: '파손', DEFECT: '불량' }

function DueModal({ value, onClose, onSubmit }: any) {
  const [v, setV] = useState(value ? String(value).slice(0, 10) : '')
  return (
    <Modal title="처리 기한" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit(v) }}>
        <label htmlFor="due-d">이 날까지 발송·처리</label><input id="due-d" type="date" value={v} onChange={e => setV(e.target.value)} />
        <p className="muted small">요청 기한을 참고해 정하세요. 비우면 기한 없음. 기한이 지나면 목록·보드에 빨간 표시가 붙습니다.</p>
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}

function ShipModal({ onClose, onSubmit }: any) {
  const [carrier, setCarrier] = useState('본사 배송')
  const [no, setNo] = useState('')
  return (
    <Modal title="발송 등록" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit({ carrier, trackingNo: no }) }}>
        <label htmlFor="sh-c">배송 방법·택배사</label><input id="sh-c" value={carrier} onChange={e => setCarrier(e.target.value)} />
        <label htmlFor="sh-n">송장번호</label><input id="sh-n" value={no} onChange={e => setNo(e.target.value)} />
        <button className="primary">발송 등록</button>
      </form>
    </Modal>
  )
}

function ReceiveModal({ lines, onClose, onSubmit }: any) {
  const [qty, setQty] = useState<Record<string, string>>(Object.fromEntries(lines.map((l: any) => [l.id, String(Number(l.qty))])))
  const [ptype, setPtype] = useState<Record<string, string>>({})
  const [res, setRes] = useState<Record<string, string>>({})
  const [memo, setMemo] = useState('')
  const problem = (l: any) => Number(l.qty) - Number(qty[l.id] || 0)
  const bad = lines.filter((l: any) => problem(l) > 0)
  return (
    <Modal title="수령 검수" onClose={onClose} wide>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit({ memo, lines: lines.map((l: any) => ({
        lineId: l.id, receivedQty: Number(qty[l.id]),
        problemType: problem(l) > 0 ? (ptype[l.id] ?? 'SHORT') : null,
        resolution: problem(l) > 0 ? (res[l.id] ?? (l.source_type === 'HQ' ? 'RESEND' : 'WRITE_OFF')) : null })) }) }}>
        <p className="muted small">정상으로 받은 수량을 넣으세요. 모자라거나 파손·불량인 수량은 아래에서 <b>재발송</b>(본사가 무상으로 다시 보냄) 또는 <b>손실·결손 처리</b>(결재 초안 생성)를 고릅니다.</p>
        <div className="table-wrap"><table className="tbl compact">
          <thead><tr><th>품목</th><th className="num">요청</th><th className="num">정상 수령</th><th>문제</th><th>처리</th></tr></thead>
          <tbody>{lines.map((l: any) => {
            const p = problem(l)
            return (
              <tr key={l.id}><td>{l.item_name}{l.source_type === 'EXTERNAL' && <> <Badge tone="accent">외부</Badge></>}</td><td className="num">{Number(l.qty)}</td>
                <td className="num"><input aria-label={`${l.item_name} 정상 수령 수량`} className={p > 0 ? 'warn-input' : ''} inputMode="decimal" style={{ width: 90, textAlign: 'right' }}
                  value={qty[l.id]} onChange={e => setQty({ ...qty, [l.id]: e.target.value })} /></td>
                <td>{p > 0 ? <select aria-label={`${l.item_name} 문제 유형`} value={ptype[l.id] ?? 'SHORT'} onChange={e => setPtype({ ...ptype, [l.id]: e.target.value })}>
                  {Object.entries(PROBLEM).map(([k, v]) => <option key={k} value={k}>{v} {p}개</option>)}</select> : <span className="muted">—</span>}</td>
                <td>{p > 0 ? <select aria-label={`${l.item_name} 처리`} value={res[l.id] ?? (l.source_type === 'HQ' ? 'RESEND' : 'WRITE_OFF')} onChange={e => setRes({ ...res, [l.id]: e.target.value })}>
                  {l.source_type === 'HQ' && <option value="RESEND">재발송 요청</option>}<option value="WRITE_OFF">손실·결손 처리</option></select> : <span className="muted">—</span>}</td>
              </tr>)
          })}</tbody>
        </table></div>
        {bad.length > 0 && <div className="note warn">문제 {bad.length}건 · 재발송은 물류 처리 보드에 무상 재발송 요청으로 바로 올라가고, 손실·결손 처리는 손실·결손 화면에 결재 초안으로 만들어집니다(사진을 붙여 상신).</div>}
        <label htmlFor="rv-memo">메모</label><input id="rv-memo" value={memo} onChange={e => setMemo(e.target.value)} />
        <button className="primary">검수 완료</button>
      </form>
    </Modal>
  )
}

function AdjustModal({ line, onClose, onSubmit }: any) {
  const [price, setPrice] = useState(String(line.list_unit_price))
  const [qty, setQty] = useState(String(Number(line.qty)))
  return (
    <Modal title={`단가·수량 조정 · ${line.item_name}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit({ unitPrice: Number(price), qty: Number(qty) }) }}>
        <label htmlFor="aj-p">단가 (공급가)</label><input id="aj-p" inputMode="numeric" value={price} onChange={e => setPrice(e.target.value.replace(/\D/g, ''))} />
        <label htmlFor="aj-q">수량</label><input id="aj-q" inputMode="decimal" value={qty} onChange={e => setQty(e.target.value)} />
        <p className="muted small">매장 할인 조건은 그대로 다시 적용됩니다. 조정 내역은 이력에 남습니다.</p>
        <button className="primary">조정</button>
      </form>
    </Modal>
  )
}
