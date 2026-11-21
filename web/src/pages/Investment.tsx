import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { won, num } from '../format'

const CAT: Record<string, string> = { INTERIOR: '인테리어', EQUIPMENT: '설비', DEPOSIT: '임차보증금', PREMIUM: '권리금', INITIAL_STOCK: '초도 물품', OTHER: '기타' }
const SRC: Record<string, string> = { INVESTOR: '투자자 출자', HQ: '본사 지원' }
const MODE: Record<string, string> = { TOTAL: '총액 공개', FIXED_AMOUNT: '지정액 공개', RATIO: '비율 공개' }

export function Investment() {
  const d = useApi<any>('/api/stl/investments')
  // 투자자 추가 후보: 투자자 등급 계정만
  const usersAll = useApi<any[]>('/api/auth/users')
  const users = { data: (usersAll.data ?? []).filter(u => u.assignments.some((a: any) => a.role === 'INVESTOR')) }
  const [add, setAdd] = useState<any>(null)
  const [disc, setDisc] = useState<any>(null)
  const [sel, setSel] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const run = async (fn: () => Promise<any>, ok: string) => { setErr(null); try { await fn(); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) } }
  const cur = d.data?.stores.find((s: any) => s.store_id === sel)
  return (
    <Page title="투자 관리" sub="총괄 관리자 전용 · 매장 구성 총액 · 조회 이력은 감사로그에 남습니다"
      actions={<button onClick={() => setDisc({})}>투자자 공개 설정</button>}>
      <ErrorBox error={d.error || err} />
      {d.data && <>
        <div className="grid3">
          <div className="card stat"><small>매장 구성 총액</small><b className="tnum">{won(d.data.grand_total)}원</b><span className="muted small">{d.data.stores.length}개 매장 · 투자자 출자 {won(d.data.grand_investor)} + 본사 지원 {won(d.data.grand_hq)}</span></div>
          <div className="card stat"><small>누적 결산액 (마감 기준)</small><b className="tnum">{won(d.data.stores.reduce((t: number, s: any) => t + s.cumulative_settlement, 0))}원</b></div>
          <div className="card stat"><small>투자자</small><b>{d.data.investors.length}명</b></div>
        </div>
        <div className="detail">
          <div className="detail-main">
            <div className="card table-wrap">
              <table className="tbl">
                <thead><tr><th>매장</th><th>권역</th>{Object.values(CAT).map(c => <th key={c} className="num">{c}</th>)}<th className="num">투자자 출자</th><th className="num">본사 지원</th><th className="num">구성 총액</th><th className="num">누적 결산</th><th className="num">회수율</th><th>공개</th></tr></thead>
                <tbody>{d.data.stores.map((s: any) => (
                  <tr key={s.store_id} className={`clickable ${sel === s.store_id ? 'selected' : ''}`} onClick={() => setSel(s.store_id)}>
                    <td><b>{s.store_name}</b></td><td>{s.region_name}</td>
                    {Object.values(CAT).map(c => <td key={c} className="num">{s.by_category[c] ? won(s.by_category[c]) : ''}</td>)}
                    <td className="num">{won(s.investor_total)}{s.total > 0 && s.investor_total > 0 && <><br /><small className="muted">{(s.investor_total / s.total * 100).toFixed(1)}%</small></>}</td><td className="num">{won(s.hq_total)}</td><td className="num"><b>{won(s.total)}</b></td><td className="num">{won(s.cumulative_settlement)}</td><td className="num">{s.recovery_rate == null ? '—' : s.recovery_rate + '%'}</td>
                    <td>{s.disclosures.map((x: any) => <Badge key={x.id} tone="accent">{x.display_name}</Badge>)}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          </div>
          <aside className="detail-side">
            {cur ? <section className="card">
              <div className="side-title"><b>{cur.store_name}</b><button className="small primary" onClick={() => setAdd({ storeId: cur.store_id })}>+ 투자 항목</button></div>
              <table className="tbl compact"><tbody>{cur.items.map((i: any) => <tr key={i.id}><td><small>{CAT[i.category]}</small><br />{i.description}<br /><small className="muted">{i.invested_on}{i.asset_id && ' · 설비 자산(감가상각)'}</small><br />
                <select aria-label="자금 출처" className="mini" value={i.funding_source === 'INVESTOR' ? i.investor_id ?? 'UNASSIGNED' : 'HQ'}
                  onChange={e => run(() => api(`/api/stl/investments/${i.id}`, { method: 'PUT', body: e.target.value === 'HQ' ? { fundingSource: 'HQ' } : { fundingSource: 'INVESTOR', investorId: e.target.value } }), '자금 출처를 바꿨습니다')}>
                  <option value="HQ">본사 지원</option>
                  {i.funding_source === 'INVESTOR' && !i.investor_id && <option value="UNASSIGNED" disabled>투자자 출자 · 투자자 미지정 (지정 필요)</option>}
                  {d.data.investors.map((v: any) => <option key={v.id} value={v.id}>투자자 출자 · {v.display_name}</option>)}</select>
                {i.funding_source === 'INVESTOR' && !i.investor_id && <div className="small overdue">투자자를 지정해야 이익금 배분·지급에 포함됩니다</div>}</td>
                <td className="num">{won(i.amount)}</td><td><button className="ghost small" aria-label="삭제" onClick={() => run(() => api(`/api/stl/investments/${i.id}`, { method: 'DELETE' }), '삭제했습니다')}>✕</button></td></tr>)}</tbody></table>
              <b>투자자 공개</b>
              {cur.disclosures.length === 0 && <p className="muted small">공개 설정 없음</p>}
              {cur.disclosures.map((x: any) => <div key={x.id} className="comment"><span>{x.display_name} · {MODE[x.mode]} {x.mode === 'RATIO' ? `${Number(x.ratio) * 100}%` : x.mode === 'FIXED_AMOUNT' ? won(x.fixed_amount) + '원' : ''}{x.show_settlement && ' · 결산·회수율 공개'}</span>
                <button className="ghost small" onClick={() => run(() => api(`/api/stl/investor-disclosures/${x.id}`, { method: 'DELETE' }), '공개를 해제했습니다')}>해제</button></div>)}
            </section> : <section className="card muted small">매장을 고르면 투자 항목과 투자자 공개 설정이 보입니다.</section>}
          </aside>
        </div>
      </>}
      {add && <AddModal storeId={add.storeId} investors={d.data?.investors ?? []} onClose={() => setAdd(null)} onSubmit={(b: any) => { setAdd(null); run(() => api('/api/stl/investments', { body: b }), '추가했습니다') }} />}
      {disc && d.data && <DiscModal data={d.data} users={users.data ?? []} storeId={sel} onClose={() => setDisc(null)} run={run} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function AddModal({ storeId, investors, onClose, onSubmit }: any) {
  const [f, setF] = useState({ category: 'INTERIOR', description: '', amount: '', investedOn: new Date().toISOString().slice(0, 10), fundingSource: 'HQ' })
  return (
    <Modal title="투자 항목 추가" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit({ ...f, storeId, amount: Number(f.amount), fundingSource: f.fundingSource === 'HQ' ? 'HQ' : 'INVESTOR', investorId: f.fundingSource === 'HQ' ? null : f.fundingSource }) }}>
        <label htmlFor="iv-cat">구분</label><select id="iv-cat" value={f.category} onChange={e => setF({ ...f, category: e.target.value })}>{Object.entries(CAT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        <label htmlFor="iv-src">자금 출처</label><select id="iv-src" value={f.fundingSource} onChange={e => setF({ ...f, fundingSource: e.target.value })}>
          <option value="HQ">본사 지원</option>{investors.map((v: any) => <option key={v.id} value={v.id}>투자자 출자 · {v.display_name}</option>)}</select>
        <label htmlFor="iv-desc">내용</label><input id="iv-desc" required value={f.description} onChange={e => setF({ ...f, description: e.target.value })} />
        <label htmlFor="iv-amt">금액</label><input id="iv-amt" required inputMode="numeric" value={num(f.amount)} onChange={e => setF({ ...f, amount: e.target.value.replace(/\D/g, '') })} />
        <label htmlFor="iv-date">일자</label><input id="iv-date" required type="date" value={f.investedOn} onChange={e => setF({ ...f, investedOn: e.target.value })} />
        <button className="primary">추가</button>
      </form>
    </Modal>
  )
}

function DiscModal({ data, users, storeId, onClose, run }: any) {
  const [investorId, setInvestorId] = useState(data.investors[0]?.id ?? '')
  const [store, setStore] = useState(storeId ?? data.stores[0]?.store_id)
  const [mode, setMode] = useState('TOTAL')
  const [fixed, setFixed] = useState('')
  const [ratio, setRatio] = useState('')
  const [show, setShow] = useState(true)
  const [newUser, setNewUser] = useState('')
  return (
    <Modal title="투자자 공개 설정" onClose={onClose}>
      <div className="form">
        <label htmlFor="ds-inv">투자자</label>
        <select id="ds-inv" value={investorId} onChange={e => setInvestorId(e.target.value)}>{data.investors.map((i: any) => <option key={i.id} value={i.id}>{i.display_name}</option>)}</select>
        <details><summary className="small">투자자 추가 (투자자 등급 계정 연결)</summary>
          <div className="row"><select aria-label="계정" value={newUser} onChange={e => setNewUser(e.target.value)}><option value="">계정 선택</option>{users.filter((u: any) => !data.investors.some((i: any) => i.user_id === u.id)).map((u: any) => <option key={u.id} value={u.id}>{u.name} ({u.loginId})</option>)}</select>
            <button type="button" disabled={!newUser} onClick={() => { const u = users.find((x: any) => x.id === newUser); run(() => api('/api/stl/investors', { body: { userId: newUser, displayName: u.name } }), '투자자를 추가했습니다'); onClose() }}>추가</button></div>
        </details>
        <label htmlFor="ds-store">매장</label><select id="ds-store" value={store} onChange={e => setStore(e.target.value)}>{data.stores.map((s: any) => <option key={s.store_id} value={s.store_id}>{s.store_name} (구성 총액 {won(s.total)})</option>)}</select>
        <label htmlFor="ds-mode">공개 방식</label><select id="ds-mode" value={mode} onChange={e => setMode(e.target.value)}>{Object.entries(MODE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        {mode === 'FIXED_AMOUNT' && <><label htmlFor="ds-fixed">공개 금액</label><input id="ds-fixed" inputMode="numeric" value={num(fixed)} onChange={e => setFixed(e.target.value.replace(/\D/g, ''))} /></>}
        {mode === 'RATIO' && <><label htmlFor="ds-ratio">지분 비율 (%)</label><input id="ds-ratio" inputMode="decimal" value={ratio} onChange={e => setRatio(e.target.value)} /></>}
        <label className="switch"><input type="checkbox" checked={show} onChange={e => setShow(e.target.checked)} /><span>결산액·회수율도 공개 (비율 반영)</span></label>
        <button className="primary" disabled={!investorId} onClick={() => { run(() => api('/api/stl/investor-disclosures', { body: { investorId, storeId: store, mode, fixedAmount: fixed ? Number(fixed) : null, ratio: ratio ? Number(ratio) / 100 : null, showSettlement: show } }), '공개 설정을 저장했습니다'); onClose() }}>저장</button>
      </div>
    </Modal>
  )
}
