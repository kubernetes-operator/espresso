import { useNavigate } from 'react-router-dom'
import { useApi, Page, Badge, ErrorBox } from '../components/ui'
import { useSession } from '../session'
import { won, dt } from '../format'
import { LR_STATUS, LR_TONE } from './logisticsShared'

const COLS: { title: string; statuses: string[]; hint: string }[] = [
  { title: '결재 진행', statuses: ['REQUESTED', 'STORE_APPROVED'], hint: '단가 조정 가능' },
  { title: '구매·출고 대기', statuses: ['LOGISTICS_APPROVED', 'PURCHASED', 'PREPARING'], hint: '외부 구매 → 발송 등록' },
  { title: '배송 중', statuses: ['SHIPPED', 'DELIVERED'], hint: '매장 검수 대기' },
  { title: '완료', statuses: ['COMPLETED'], hint: '최근 완료' },
]

export function LogisticsBoard() {
  const s = useSession()
  const nav = useNavigate()
  const list = useApi<any[]>(`/api/core/logistics${s.storeId !== 'ALL' && s.stores.length <= 1 ? `?storeId=${s.storeId}` : ''}`, [s.storeId])
  return (
    <Page title="물류 처리" sub="담당 매장 전체 · 카드를 눌러 처리">
      <ErrorBox error={list.error} />
      <div className="board">
        {COLS.map(c => {
          const items = (list.data ?? []).filter(r => c.statuses.includes(r.status)).slice(0, c.title === '완료' ? 10 : 100)
          return (
            <section key={c.title} className="board-col">
              <div className="board-head"><b>{c.title}</b> <Badge>{items.length}</Badge><small className="muted">{c.hint}</small></div>
              {items.map(r => (
                <button key={r.id} className="board-card" onClick={() => nav(`/logistics/${r.id}`)}>
                  <div className="bc-top"><code>{r.request_no}</code><Badge tone={LR_TONE[r.status]}>{LR_STATUS[r.status]}</Badge></div>
                  <b>{r.store_name}</b>
                  <span className="small">{r.title || '—'} · {r.line_count}개 품목{r.external_count > 0 ? ` · 외부 ${r.external_count}` : ''}</span>
                  <span className="small muted">{won(r.total_amount)}원 · 요청 {dt(r.requested_at)}</span>
                </button>
              ))}
            </section>
          )
        })}
      </div>
    </Page>
  )
}
