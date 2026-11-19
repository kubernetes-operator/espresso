import { useState } from 'react'
import { api } from '../api'
import { prepareUpload } from '../upload'
import { dt, ROLE_NAMES } from '../format'
import { useApi, Badge } from './ui'
import { useNames } from './directory'
import { useSession } from '../session'

export const STEP_TONE: Record<string, any> = { APPROVED: 'ok', REJECTED: 'bad', PENDING: 'warn', WAITING: 'neutral', SKIPPED: 'neutral' }
export const STEP_LABEL: Record<string, string> = { APPROVED: '승인', REJECTED: '반려', PENDING: '대기 중', WAITING: '예정', SKIPPED: '통과' }

/** 결재선 + 승인/반려 (요구 10, 19) */
export function ApprovalPanel({ approval, onDone }: { approval: any; onDone: () => void }) {
  const s = useSession()
  const name = useNames()
  const [comment, setComment] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (!approval || !approval.id) return <div className="muted small">아직 상신하지 않았습니다.</div>
  const pending = approval.steps?.find((x: any) => x.status === 'PENDING')
  const canAct = approval.status === 'IN_PROGRESS' && pending && (pending.assignees.includes(s.user?.id) || s.has('HQ_ADMIN'))
  const isOverride = canAct && !pending.assignees.includes(s.user?.id)
  const canWithdraw = approval.status === 'IN_PROGRESS' && approval.requested_by === s.user?.id && !approval.steps.some((x: any) => x.status === 'APPROVED')
  const act = async (kind: 'approve' | 'reject' | 'withdraw') => {
    setErr(null); setBusy(true)
    try { await api(`/api/core/approvals/${approval.id}/${kind}`, { body: kind === 'withdraw' ? {} : { comment } }); setComment(''); onDone() }
    catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <div className="approval">
      <div className="approval-head">
        <b>결재</b>
        <Badge tone={approval.status === 'APPROVED' ? 'ok' : approval.status === 'REJECTED' ? 'bad' : approval.status === 'IN_PROGRESS' ? 'warn' : 'neutral'}>
          {({ IN_PROGRESS: '진행 중', APPROVED: '최종 승인', REJECTED: '반려', WITHDRAWN: '회수' } as any)[approval.status]}
        </Badge>
        <small className="muted">상신 {dt(approval.requested_at)} · {name(approval.requested_by)}</small>
      </div>
      <ol className="steps">
        {approval.steps.map((st: any) => (
          <li key={st.id} className={`step ${st.status.toLowerCase()}`}>
            <div className="step-top">
              <b>{st.approverType === 'USER' ? '지정 승인자' : ROLE_NAMES[st.role] ?? st.role}</b>
              <Badge tone={STEP_TONE[st.status]}>{STEP_LABEL[st.status]}</Badge>
              {st.override && <Badge tone="accent">직권</Badge>}
            </div>
            <small className="muted">
              {st.status === 'APPROVED' || st.status === 'REJECTED' ? `${name(st.actedBy)} · ${dt(st.actedAt)}`
                : st.status === 'PENDING' ? `승인자: ${st.assignees.map(name).join(', ')} · ${dt(st.activatedAt)}부터`
                : st.assignees.length ? `승인자: ${st.assignees.map(name).join(', ')}` : ''}
            </small>
            {st.comment && <div className="step-comment">{st.comment}</div>}
          </li>
        ))}
      </ol>
      {canAct && (
        <div className="act">
          <label htmlFor="appr-comment" className="small muted">의견 {isOverride && '(총괄 직권 처리)'}</label>
          <textarea id="appr-comment" rows={2} value={comment} onChange={e => setComment(e.target.value)} placeholder="반려할 때는 사유가 필요합니다" />
          {err && <div className="error-box">{err}</div>}
          <div className="act-buttons">
            <button className="danger" disabled={busy} onClick={() => act('reject')}>반려</button>
            <button className="primary" disabled={busy} onClick={() => act('approve')}>승인</button>
          </div>
        </div>
      )}
      {canWithdraw && <button className="ghost small" disabled={busy} onClick={() => act('withdraw')}>상신 회수</button>}
    </div>
  )
}

/** 요청·승인·완료 시각 타임라인 (요구 22) */
export function Timeline({ history, labels }: { history: any[]; labels: Record<string, string> }) {
  const name = useNames()
  return (
    <ol className="timeline">
      {history.map((h, i) => (
        <li key={i}>
          <div className="tl-dot" />
          <div><b>{labels[h.to_status] ?? h.to_status}</b> <small className="muted">{dt(h.occurred_at)} · {name(h.actor_id)}</small>
            {h.memo && <div className="small">{h.memo}</div>}</div>
        </li>
      ))}
    </ol>
  )
}

/** 메모 스레드 + 첨부 (요구 19) */
export function Comments({ docType, docId }: { docType: string; docId: string }) {
  const name = useNames()
  const list = useApi<any[]>(`/api/core/docs/${docType}/${docId}/comments`)
  const files = useApi<any[]>(`/api/core/docs/${docType}/${docId}/attachments`)
  const [body, setBody] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const add = async () => {
    setErr(null)
    try { await api(`/api/core/docs/${docType}/${docId}/comments`, { body: { body } }); setBody(''); list.reload() } catch (e: any) { setErr(e.message) }
  }
  const upload = async (f: File) => {
    setErr(null)
    try {
      const fd = new FormData(); fd.append('file', await prepareUpload(f))
      await api(`/api/core/docs/${docType}/${docId}/attachments`, { body: fd }); files.reload()
    } catch (e: any) { setErr(e.message) }
  }
  const open = async (a: any) => {
    const res: Response = await api(`/api/core/docs/${docType}/${docId}/attachments/${a.id}`, { raw: true })
    const url = URL.createObjectURL(await res.blob())
    window.open(url, '_blank')
  }
  return (
    <div className="comments">
      <b>메모</b>
      {(list.data ?? []).map(c => (
        <div key={c.id} className="comment"><small className="muted">{name(c.author_id)} · {dt(c.created_at)}</small><div>{c.body}</div></div>
      ))}
      {list.data?.length === 0 && <div className="muted small">메모가 없습니다.</div>}
      <div className="comment-new">
        <textarea id={`memo-${docId}`} rows={2} value={body} onChange={e => setBody(e.target.value)} placeholder="메모 입력" aria-label="메모 입력" />
        <button onClick={add} disabled={!body.trim()}>등록</button>
      </div>
      <div className="files">
        {(files.data ?? []).map(a => <button key={a.id} className="ghost small" onClick={() => open(a)}>📎 {a.file_name}</button>)}
        <label className="file-btn">사진·파일 첨부<input type="file" accept="image/*,application/pdf" capture="environment" onChange={e => e.target.files?.[0] && upload(e.target.files[0])} hidden /></label>
      </div>
      {err && <div className="error-box">{err}</div>}
    </div>
  )
}
