import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useApi, Page, Badge, ErrorBox, Empty } from '../components/ui'
import { useSession } from '../session'
import { won, dt } from '../format'
import { LR_STATUS, LR_TONE } from './logisticsShared'

export function Logistics() {
  const s = useSession()
  const nav = useNavigate()
  const [status, setStatus] = useState('')
  const q = new URLSearchParams()
  if (s.storeId !== 'ALL') q.set('storeId', s.storeId)
  if (status) q.set('status', status)
  const list = useApi<any[]>(`/api/core/logistics?${q}`, [s.storeId])
  const canCreate = s.has('HQ_ADMIN', 'LOGISTICS_MGR', 'OWNER', 'STORE_MGR', 'STAFF')
  const inc = s.vatMode === 'INCLUSIVE'
  return (
    <Page title="물류 요청" sub={`${s.currentStore?.name ?? '전체 매장'} · 금액은 VAT ${inc ? '포함' : '별도'} 기준`}
      actions={<>
        <select id="lr-status" aria-label="상태" value={status} onChange={e => setStatus(e.target.value)}>
          <option value="">전체 상태</option>
          {Object.entries(LR_STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        {canCreate && <button className="primary" onClick={() => nav('/logistics/new')}>+ 물류 요청</button>}
      </>}>
      <ErrorBox error={list.error} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>번호</th><th>매장</th><th>제목</th><th>상태</th><th className="num">품목</th><th className="num">금액</th><th>요청</th><th>승인</th><th>완료</th></tr></thead>
          <tbody>
            {(list.data ?? []).map(r => (
              <tr key={r.id} className="clickable" onClick={() => nav(`/logistics/${r.id}`)}>
                <td><code>{r.request_no}</code>{r.is_initial_order && <> <Badge tone="info">초도</Badge></>}</td>
                <td>{r.store_name}</td>
                <td>{r.title || '—'}{r.external_count > 0 && <> <Badge tone="accent">외부 {r.external_count}</Badge></>}</td>
                <td><Badge tone={LR_TONE[r.status]}>{LR_STATUS[r.status]}</Badge></td>
                <td className="num">{r.line_count}</td>
                <td className="num">{won(inc ? r.total_amount : r.taxable_supply + r.exempt_supply + r.shipping_fee)}</td>
                <td>{dt(r.requested_at)}</td><td>{dt(r.approved_at)}</td><td>{dt(r.completed_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {list.data?.length === 0 && <Empty>물류 요청이 없습니다.</Empty>}
      </div>
    </Page>
  )
}
