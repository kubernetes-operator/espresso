import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { won, ROLE_NAMES } from '../format'
import { useNames } from '../components/directory'

const DOCS: Record<string, string> = { LOGISTICS_REQUEST: '물류 요청', STOCK_ADJUSTMENT: '손실·결손·내부사용', PAYROLL_RUN: '급여 확정', EXPENSE: '비용' }
const APPROVER_ROLES = ['STORE_MGR', 'OWNER', 'LOGISTICS_MGR', 'HR_MGR', 'SUPERVISOR', 'HQ_ADMIN']

export function AdminApprovalLines() {
  const lines = useApi<any[]>('/api/core/approvals/lines')
  const stores = useApi<any[]>('/api/core/stores/names')
  const users = useApi<any[]>('/api/auth/directory')
  const name = useNames()
  const [edit, setEdit] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const del = async (id: string) => {
    setErr(null)
    try { await api(`/api/core/approvals/lines/${id}`, { method: 'DELETE' }); setMsg('결재선을 지웠습니다'); lines.reload() } catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="결재선" sub="기본은 금액과 관계없이 전체 결재. 매장·금액 구간별로 줄이거나 승인자를 지정할 수 있습니다"
      actions={<button className="primary" onClick={() => setEdit({ doc_type: 'LOGISTICS_REQUEST', steps: [{ approver_type: 'ROLE', role_code: 'LOGISTICS_MGR' }] })}>+ 결재선</button>}>
      <ErrorBox error={lines.error || err} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>문서</th><th>이름</th><th>적용 매장</th><th>금액 구간</th><th>결재 순서</th><th></th></tr></thead>
          <tbody>
            {(lines.data ?? []).map(l => (
              <tr key={l.id}>
                <td>{DOCS[l.doc_type] ?? l.doc_type}</td>
                <td>{l.name} {l.is_default && <Badge tone="info">기본</Badge>}</td>
                <td>{l.store_name ?? '전 매장'}</td>
                <td>{l.min_amount == null && l.max_amount == null ? '전체' : `${l.min_amount != null ? won(l.min_amount) : '0'} ~ ${l.max_amount != null ? won(l.max_amount) + ' 미만' : ''}`}</td>
                <td>{l.steps.map((st: any) => st.approver_type === 'USER' ? `지정: ${name(st.user_id)}` : ROLE_NAMES[st.role_code]).join(' → ')}</td>
                <td className="nowrap"><button className="ghost small" onClick={() => setEdit(l)}>수정</button>{!l.is_default && <button className="ghost small" onClick={() => del(l.id)}>삭제</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">선택 순서: 매장 지정 결재선 → 금액 구간 결재선 → 기본 결재선. 요청자 등급 이하 단계는 자동 통과합니다.</p>
      {edit && <LineModal line={edit} stores={stores.data ?? []} users={users.data ?? []} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setMsg('저장했습니다'); lines.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function LineModal({ line, stores, users, onClose, onDone }: any) {
  const isNew = !line.id
  const [docType, setDocType] = useState(line.doc_type)
  const [nm, setNm] = useState(line.name ?? '')
  const [storeId, setStoreId] = useState(line.store_id ?? '')
  const [min, setMin] = useState(line.min_amount != null ? String(line.min_amount) : '')
  const [max, setMax] = useState(line.max_amount != null ? String(line.max_amount) : '')
  const [steps, setSteps] = useState<any[]>(line.steps.map((s: any) => ({ approverType: s.approver_type, roleCode: s.role_code, userId: s.user_id })))
  const [err, setErr] = useState<string | null>(null)
  const set = (i: number, p: any) => setSteps(ss => ss.map((s, j) => j === i ? { ...s, ...p } : s))
  const save = async () => {
    setErr(null)
    const body = { docType, name: nm || `${DOCS[docType]} 결재선`, storeId: storeId || null, minAmount: min ? Number(min) : null, maxAmount: max ? Number(max) : null, steps }
    try {
      if (isNew) await api('/api/core/approvals/lines', { body }); else await api(`/api/core/approvals/lines/${line.id}`, { method: 'PUT', body })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={isNew ? '결재선 추가' : '결재선 수정'} onClose={onClose} wide>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="al-doc">문서</label><select id="al-doc" value={docType} disabled={!isNew} onChange={e => setDocType(e.target.value)}>{Object.entries(DOCS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <div><label htmlFor="al-name">이름</label><input id="al-name" value={nm} onChange={e => setNm(e.target.value)} /></div>
          <div><label htmlFor="al-store">적용 매장</label><select id="al-store" value={storeId} disabled={!isNew} onChange={e => setStoreId(e.target.value)}><option value="">전 매장</option>{stores.map((s: any) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></div>
        </div>
        {!line.is_default && <div className="row">
          <div><label htmlFor="al-min">금액 이상 (원)</label><input id="al-min" inputMode="numeric" value={min} onChange={e => setMin(e.target.value.replace(/\D/g, ''))} /></div>
          <div><label htmlFor="al-max">금액 미만 (원)</label><input id="al-max" inputMode="numeric" value={max} onChange={e => setMax(e.target.value.replace(/\D/g, ''))} /></div>
        </div>}
        <label>결재 순서</label>
        {steps.map((st, i) => (
          <div key={i} className="step-edit">
            <span className="muted">{i + 1}</span>
            <select aria-label="승인자 유형" value={st.approverType} onChange={e => set(i, { approverType: e.target.value })}><option value="ROLE">등급</option><option value="USER">지정 승인자</option></select>
            {st.approverType === 'ROLE'
              ? <select aria-label="등급" value={st.roleCode ?? ''} onChange={e => set(i, { roleCode: e.target.value })}>{APPROVER_ROLES.map(r => <option key={r} value={r}>{ROLE_NAMES[r]}</option>)}</select>
              : <select aria-label="승인자" value={st.userId ?? ''} onChange={e => set(i, { userId: e.target.value })}><option value="">선택</option>{users.map((u: any) => <option key={u.id} value={u.id}>{u.name} ({u.login_id})</option>)}</select>}
            <button type="button" className="ghost small" disabled={i === 0} onClick={() => setSteps(ss => { const c = [...ss]; [c[i - 1], c[i]] = [c[i], c[i - 1]]; return c })}>↑</button>
            <button type="button" className="ghost small" onClick={() => setSteps(ss => ss.filter((_, j) => j !== i))}>✕</button>
          </div>
        ))}
        <button type="button" onClick={() => setSteps(ss => [...ss, { approverType: 'ROLE', roleCode: 'HQ_ADMIN' }])}>+ 단계</button>
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}
