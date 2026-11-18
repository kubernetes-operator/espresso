import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useApi, Page, Badge, ErrorBox, Empty, Toast } from '../components/ui'
import { ApprovalPanel, STEP_LABEL, STEP_TONE } from '../components/DocPanels'
import { useSession } from '../session'
import { won, dt, ROLE_NAMES } from '../format'
import { useNames } from '../components/directory'

const BOXES = [['pending', '내 결재 대기'], ['mine', '내가 올린 문서'], ['done', '내가 처리한 문서']] as const

export function Approvals() {
  const s = useSession()
  const nav = useNavigate()
  const name = useNames()
  const [box, setBox] = useState<string>('pending')
  const list = useApi<any[]>(`/api/core/approvals?box=${box}`)
  const [sel, setSel] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const boxes = s.has('HQ_ADMIN') ? [...BOXES, ['all', '진행 중 전체'] as const] : BOXES
  const cur = (list.data ?? []).find(r => r.id === sel)

  return (
    <Page title="결재함" sub="요청·승인·완료 시각이 모두 기록됩니다"
      actions={<div className="seg">{boxes.map(([k, v]) => <button key={k} className={box === k ? 'on' : ''} onClick={() => { setBox(k); setSel(null) }}>{v}</button>)}</div>}>
      <ErrorBox error={list.error} />
      <div className="detail">
        <div className="detail-main">
          <div className="card table-wrap">
            <table className="tbl">
              <thead><tr><th>문서</th><th>번호</th><th>매장</th><th>내용</th><th className="num">금액</th><th>진행</th><th>상신</th></tr></thead>
              <tbody>
                {(list.data ?? []).map(r => {
                  const p = r.steps.find((x: any) => x.status === 'PENDING')
                  return (
                    <tr key={r.id} className={`clickable ${sel === r.id ? 'selected' : ''}`} onClick={() => setSel(r.id)}>
                      <td><Badge tone="info">{r.docLabel}</Badge></td>
                      <td><code>{r.doc.no}</code></td>
                      <td>{r.doc.storeName}</td>
                      <td>{r.doc.title}</td>
                      <td className="num">{won(r.doc.amount)}</td>
                      <td>{r.status === 'IN_PROGRESS' ? <Badge tone="warn">{p ? `${ROLE_NAMES[p.role] ?? '지정'} 대기` : '진행'}</Badge>
                        : <Badge tone={r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'bad' : 'neutral'}>{({ APPROVED: '최종 승인', REJECTED: '반려', WITHDRAWN: '회수' } as any)[r.status]}</Badge>}</td>
                      <td>{dt(r.requested_at)} <small className="muted">{name(r.requested_by)}</small></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {list.data?.length === 0 && <Empty>{box === 'pending' ? '결재할 문서가 없습니다.' : '문서가 없습니다.'}</Empty>}
          </div>
        </div>
        <aside className="detail-side">
          {cur ? (
            <section className="card">
              <div className="side-title"><Badge tone="info">{cur.docLabel}</Badge> <code>{cur.doc.no}</code></div>
              <p className="small">{cur.doc.storeName} · {cur.doc.title} · <b>{won(cur.doc.amount)}원</b></p>
              <button className="small" onClick={() => nav(cur.doc.link)}>문서 열기 →</button>
              <ApprovalPanel approval={cur} onDone={() => { setMsg('처리했습니다'); setSel(null); list.reload() }} />
            </section>
          ) : <section className="card muted small">왼쪽 목록에서 문서를 고르면 결재선과 승인·반려 버튼이 나옵니다.</section>}
        </aside>
      </div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
