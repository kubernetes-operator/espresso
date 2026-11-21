import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Modal, Toast, Empty } from '../components/ui'
import { ApprovalPanel } from '../components/DocPanels'
import { useSession } from '../session'
import { won, num } from '../format'

const KIND: Record<string, string> = { RENT: '임대', UTILITY: '공과금(사용료)', OPERATING: '운영비', NON_OPERATING: '영업외' }
const KIND_ACC: Record<string, string[]> = { RENT: ['819', '837'], UTILITY: ['815', '814'], OPERATING: ['820', '831', '833', '821', '817', '822', '825', '848', '824', '830'], NON_OPERATING: ['931', '980'] }
const ST: Record<string, string> = { DRAFT: '작성 중', REQUESTED: '결재 중', APPROVED: '승인', REJECTED: '반려' }
const TONE: Record<string, any> = { DRAFT: 'neutral', REQUESTED: 'warn', APPROVED: 'ok', REJECTED: 'bad' }
const thisMonth = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 7)

export function Expenses() {
  const s = useSession()
  const [month, setMonth] = useState(thisMonth())
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const list = useApi<any[]>(`/api/stl/expenses?month=${month}${storeId ? `&storeId=${storeId}` : ''}`, [s.storeId, month])
  const accounts = useApi<any[]>('/api/stl/accounts')
  const [creating, setCreating] = useState(false)
  const [sel, setSel] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const inc = s.vatMode === 'INCLUSIVE'
  const rows = list.data ?? []
  const byKind = Object.keys(KIND).map(k => ({ k, v: rows.filter(r => r.expense_kind === k && r.status === 'APPROVED').reduce((t, r) => t + Number(inc ? r.total_amount : r.supply_amount), 0) }))
  const approval = useApi<any>(sel ? `/api/core/approvals/doc/EXPENSE/${sel.id}` : null, [sel?.id])
  const [editing, setEditing] = useState<any>(null)
  const recurring = async () => {
    setErr(null)
    try { const r: any = await api('/api/stl/expenses/recurring', { body: { storeId, month } }); setMsg(r.created ? `월세·관리비 ${r.created}건을 만들었습니다` : '이미 만들어져 있습니다'); list.reload() } catch (e: any) { setErr(e.message) }
  }
  const act = async (id: string, p: string, ok: string) => { setErr(null); try { await api(`/api/stl/expenses/${id}/${p}`, { body: {} }); setMsg(ok); list.reload() } catch (e: any) { setErr(e.message) } }
  return (
    <Page title="비용" sub="임대 · 공과금(전기·수도·가스·통신) · 운영비 — 결재: 매장관리자 → 점주 → 총괄"
      actions={<>
        <input id="exp-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
        {storeId && s.has('HQ_ADMIN', 'OWNER') && <button onClick={recurring}>월세·관리비 생성</button>}
        {storeId && <button className="primary" onClick={() => setCreating(true)}>+ 비용</button>}
      </>}>
      <ErrorBox error={list.error || err} />
      <div className="grid3">{byKind.map(k => <div key={k.k} className="card stat"><small>{KIND[k.k]} (승인분)</small><b className="tnum">{won(k.v)}원</b></div>)}</div>
      <div className="detail">
        <div className="detail-main">
          <div className="card table-wrap">
            <table className="tbl">
              <thead><tr><th>번호</th><th>일자</th><th>구분</th><th>계정</th><th>거래처</th><th>사용 기간</th><th className="num">공급가</th><th className="num">세액</th><th className="num">합계</th><th>결제</th><th>상태</th><th></th></tr></thead>
              <tbody>{rows.map(r => (
                <tr key={r.id} className={`clickable ${sel?.id === r.id ? 'selected' : ''}`} onClick={() => setSel(r)}>
                  <td><code>{r.expense_no}</code></td><td>{r.expense_date}</td><td>{KIND[r.expense_kind]}</td><td>{r.account_name}</td><td>{r.vendor_name}</td>
                  <td><small>{r.usage_from ? `${String(r.usage_from).slice(5)} ~ ${String(r.usage_to ?? '').slice(5)}` : '—'}</small></td>
                  <td className="num">{won(r.supply_amount)}</td><td className="num">{r.tax_type === 'EXEMPT' ? '0 (면세)' : won(r.vat_amount)}</td><td className="num">{won(r.total_amount)}</td>
                  <td>{r.payer === 'HQ' ? <Badge tone="accent">본사 대납</Badge> : '매장'}</td>
                  <td><Badge tone={TONE[r.status]}>{ST[r.status]}</Badge></td>
                  <td className="nowrap" onClick={e => e.stopPropagation()}>
                    {r.status === 'DRAFT' && <button className="small" onClick={() => act(r.id, 'submit', '상신했습니다')}>상신</button>}
                    {(['DRAFT', 'REJECTED'].includes(r.status) || (r.expense_kind === 'RENT' && r.status === 'APPROVED' && r.payer !== 'HQ' && s.has('HQ_ADMIN', 'OWNER')))
                      && (r.expense_kind !== 'RENT' || s.has('HQ_ADMIN', 'OWNER')) && <button className="ghost small" onClick={() => setEditing(r)}>수정</button>}
                    {['DRAFT', 'REJECTED'].includes(r.status) && <button className="ghost small" onClick={() => act(r.id, 'cancel', '취소했습니다')}>취소</button>}
                  </td>
                </tr>))}</tbody>
            </table>
            {rows.length === 0 && <Empty>이 달 비용이 없습니다.</Empty>}
          </div>
        </div>
        <aside className="detail-side">
          {sel ? <section className="card"><div className="side-title"><code>{sel.expense_no}</code> {sel.memo?.startsWith('정기:') && <Badge tone="info">계약 정기</Badge>}</div>
            <p className="small">{sel.vendor_name} · {won(sel.total_amount)}원{sel.reject_comment && <><br />반려 사유: {sel.reject_comment}</>}</p>
            {sel.memo?.startsWith('정기:') ? <p className="muted small">임대 계약에 따른 고정비라 결재 없이 승인됩니다.</p>
              : <ApprovalPanel approval={approval.data} onDone={() => { setMsg('처리했습니다'); approval.reload(); list.reload() }} />}
          </section> : <section className="card muted small">비용을 고르면 결재 진행이 보입니다.</section>}
        </aside>
      </div>
      {editing && <ExpenseModal storeId={storeId} accounts={accounts.data ?? []} canRent={s.has('HQ_ADMIN', 'OWNER')} row={editing} onClose={() => setEditing(null)} onDone={(m: string) => { setEditing(null); setMsg(m); list.reload() }} />}
      {creating && <ExpenseModal storeId={storeId} accounts={accounts.data ?? []} canRent={s.has('HQ_ADMIN', 'OWNER')} onClose={() => setCreating(false)} onDone={(m: string) => { setCreating(false); setMsg(m); list.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function ExpenseModal({ storeId, accounts, canRent, onClose, onDone, row }: any) {
  const editing = !!row
  const [f, setF] = useState(row ? { expenseKind: row.expense_kind, accountCode: row.account_code, vendorName: row.vendor_name ?? '', expenseDate: String(row.expense_date).slice(0, 10),
    usageFrom: row.usage_from ? String(row.usage_from).slice(0, 10) : '', usageTo: row.usage_to ? String(row.usage_to).slice(0, 10) : '', taxType: row.tax_type, amount: String(row.supply_amount),
    amountMode: 'EXCLUSIVE', payer: row.payer, evidenceType: row.evidence_type ?? 'BILL', memo: row.memo?.startsWith('정기:') ? '' : row.memo ?? '' }
    : { expenseKind: 'UTILITY', accountCode: '815', vendorName: '', expenseDate: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10),
    usageFrom: '', usageTo: '', taxType: 'TAXABLE', amount: '', amountMode: 'INCLUSIVE', payer: 'STORE', evidenceType: 'BILL', memo: '' })
  const [err, setErr] = useState<string | null>(null)
  const accs = accounts.filter((a: any) => KIND_ACC[f.expenseKind]?.includes(a.code))
  const save = async (submit: boolean) => {
    setErr(null)
    const body = { ...f, storeId, amount: Number(f.amount), usageFrom: f.usageFrom || null, usageTo: f.usageTo || null, submit, memo: f.memo || null }
    try {
      if (editing) {
        const r: any = await api(`/api/stl/expenses/${row.id}`, { method: 'PUT', body })
        if (submit && row.status === 'DRAFT') await api(`/api/stl/expenses/${row.id}/submit`, { body: {} })
        onDone(r.reposted ? '수정했습니다 (기존 분개 역분개 후 다시 반영)' : '수정했습니다')
      } else { await api('/api/stl/expenses', { body }); onDone(submit ? '상신했습니다' : '저장했습니다') }
    }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={editing ? `비용 수정 · ${row.expense_no}` : '비용 입력'} onClose={onClose}>
      {editing && row.status === 'APPROVED' && <div className="note warn small">승인된 임대 비용입니다. 저장하면 기존 분개를 역분개하고 새 금액·기간으로 다시 반영합니다 (이력 보존).</div>}
      <form className="form" onSubmit={e => { e.preventDefault(); save(true) }}>
        <div className="row">
          <div><label htmlFor="ex-kind">구분</label><select id="ex-kind" required disabled={editing} value={f.expenseKind} onChange={e => setF({ ...f, expenseKind: e.target.value, accountCode: KIND_ACC[e.target.value][0] })}>
            {Object.entries(KIND).filter(([k]) => canRent || k !== 'RENT').map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <div><label htmlFor="ex-acc">계정과목</label><select id="ex-acc" required value={f.accountCode} onChange={e => setF({ ...f, accountCode: e.target.value })}>{accs.map((a: any) => <option key={a.code} value={a.code}>{a.name}</option>)}</select></div>
        </div>
        <div className="row">
          <div><label htmlFor="ex-vendor">거래처</label><input id="ex-vendor" value={f.vendorName} onChange={e => setF({ ...f, vendorName: e.target.value })} placeholder="예: 한국전력" /></div>
          <div><label htmlFor="ex-date">일자</label><input id="ex-date" required type="date" value={f.expenseDate} onChange={e => setF({ ...f, expenseDate: e.target.value })} /></div>
        </div>
        {['UTILITY', 'RENT'].includes(f.expenseKind) && <div className="row">
          <div><label htmlFor="ex-uf">{f.expenseKind === 'RENT' ? '임대 기간 시작' : '사용 기간 시작'}</label><input id="ex-uf" type="date" required={f.expenseKind === 'RENT'} value={f.usageFrom} onChange={e => setF({ ...f, usageFrom: e.target.value })} /></div>
          <div><label htmlFor="ex-ut">{f.expenseKind === 'RENT' ? '임대 기간 끝' : '사용 기간 끝'}</label><input id="ex-ut" type="date" required={f.expenseKind === 'RENT'} value={f.usageTo} onChange={e => setF({ ...f, usageTo: e.target.value })} /></div>
        </div>}
        <div className="row">
          <div><label htmlFor="ex-amt">금액</label><input id="ex-amt" required inputMode="numeric" value={num(f.amount)} onChange={e => setF({ ...f, amount: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="ex-mode">금액 기준</label><select id="ex-mode" value={f.amountMode} onChange={e => setF({ ...f, amountMode: e.target.value })}><option value="INCLUSIVE">VAT 포함</option><option value="EXCLUSIVE">VAT 별도</option></select></div>
          <div><label htmlFor="ex-tax">과세</label><select id="ex-tax" value={f.taxType} onChange={e => setF({ ...f, taxType: e.target.value })}><option value="TAXABLE">과세</option><option value="EXEMPT">면세</option></select></div>
        </div>
        <div className="row">
          <div><label htmlFor="ex-payer">결제</label><select id="ex-payer" value={f.payer} onChange={e => setF({ ...f, payer: e.target.value })}><option value="STORE">매장 결제</option><option value="HQ">본사 대납 (정산서 청구)</option></select></div>
          <div><label htmlFor="ex-ev">증빙</label><select id="ex-ev" value={f.evidenceType} onChange={e => setF({ ...f, evidenceType: e.target.value })}><option value="TAX_INVOICE">세금계산서</option><option value="CARD">카드</option><option value="CASH_RECEIPT">현금영수증</option><option value="BILL">고지서</option><option value="NONE">없음</option></select></div>
        </div>
        <label htmlFor="ex-memo">메모</label><input id="ex-memo" value={f.memo} onChange={e => setF({ ...f, memo: e.target.value })} />
        {err && <div className="error-box">{err}</div>}
        <div className="act-buttons">
          {(!editing || row.status !== 'APPROVED') && <button type="button" onClick={() => save(false)}>{editing ? '저장' : '임시저장'}</button>}
          <button className="primary">{editing && row.status === 'APPROVED' ? '수정 반영' : editing && row.status === 'REJECTED' ? '저장' : '상신'}</button></div>
      </form>
    </Modal>
  )
}
