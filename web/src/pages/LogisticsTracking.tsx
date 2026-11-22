import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Empty, Modal } from '../components/ui'
import { useSession } from '../session'
import { won, dt } from '../format'
import { LR_STATUS, LR_TONE, overdue, dueText } from './logisticsShared'

const TRACK = ['LOGISTICS_APPROVED', 'PURCHASED', 'PREPARING', 'SHIPPED', 'DELIVERED', 'COMPLETED']
const STEPS: [string, string][] = [['approved_at', '승인'], ['shipped_at', '발송'], ['delivered_at', '도착'], ['completed_at', '수령 검수'], ['closed_at', '확인 완료']]

/** 물류 추적: 모든 승인이 끝난 요청을 단계별로 보고, 수령 검수가 끝나면 물류 담당이 확인 완료(종결) */
export function LogisticsTracking() {
  const s = useSession()
  const nav = useNavigate()
  const one = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') !== 'HQ'
  const list = useApi<any[]>(`/api/core/logistics${one ? `?storeId=${s.storeId}` : ''}`, [s.storeId])
  const [closeFor, setCloseFor] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const rows = (list.data ?? []).filter(r => TRACK.includes(r.status))
  const waiting = rows.filter(r => r.status === 'COMPLETED')
  const moving = rows.filter(r => r.status !== 'COMPLETED')
  const closed = (list.data ?? []).filter(r => r.status === 'CLOSED').slice(0, 20)
  const table = (items: any[], withClose: boolean) => (
    <div className="card table-wrap"><table className="tbl">
      <thead><tr><th>번호</th><th>매장</th><th>제목</th><th>상태</th><th>기한</th>{STEPS.map(([, l]) => <th key={l}>{l}</th>)}<th className="num">금액</th><th></th></tr></thead>
      <tbody>{items.map(r => (
        <tr key={r.id} className="clickable" onClick={() => nav(`/logistics/${r.id}`)}>
          <td><code>{r.request_no}</code>{r.kind === 'RESEND' && <> <Badge tone="accent">재발송</Badge></>}</td><td>{r.store_name}</td><td>{r.title || '—'}</td>
          <td><Badge tone={LR_TONE[r.status]}>{LR_STATUS[r.status]}</Badge></td>
          <td className={overdue(r) ? 'overdue' : ''}><small>{dueText(r)}</small></td>
          {STEPS.map(([k]) => <td key={k}><small>{r[k] ? dt(r[k]) : <span className="muted">—</span>}</small></td>)}
          <td className="num">{won(r.total_amount)}</td>
          <td onClick={e => e.stopPropagation()}>{withClose && <button className="small primary" onClick={() => setCloseFor(r)}>확인 완료</button>}</td>
        </tr>))}</tbody>
    </table>{items.length === 0 && <Empty>없습니다.</Empty>}</div>)
  return (
    <Page title="물류 추적" sub={`${one ? s.currentStore?.name : '담당 매장 전체'} · 승인 완료 후 구매·발송·도착·수령 검수 → 물류 담당 확인 완료`}>
      <ErrorBox error={list.error || err} />
      <h2>확인 대기 <Badge tone="info">{waiting.length}</Badge></h2>
      <p className="muted small">매장 수령 검수까지 끝났습니다. 수량·문제 처리(재발송·손실)를 확인하고 [확인 완료]를 누르세요.</p>
      {table(waiting, true)}
      <h2>진행 중 <Badge>{moving.length}</Badge></h2>
      {table(moving, false)}
      <h2>최근 확인 완료</h2>
      {table(closed, false)}
      {closeFor && <Modal title={`확인 완료 · ${closeFor.request_no}`} onClose={() => setCloseFor(null)}>
        <form className="form" onSubmit={async e => {
          e.preventDefault()
          const memo = (new FormData(e.currentTarget).get('memo') as string) || null
          setErr(null)
          try { await api(`/api/core/logistics/${closeFor.id}/close`, { body: { memo } }); setMsg('확인 완료했습니다'); setCloseFor(null); list.reload() } catch (x: any) { setErr(x.message); setCloseFor(null) }
        }}>
          <label htmlFor="cl-memo">확인 메모 (선택)</label><input id="cl-memo" name="memo" placeholder="예: 수량·송장 확인" />
          <button className="primary">확인 완료</button>
        </form>
      </Modal>}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
