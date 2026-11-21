import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { won, num, dt, DIVISIONS } from '../format'

const kstMonth = (offset = 0) => { const d = new Date(Date.now() + 9 * 3600e3); d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() + offset); return d.toISOString().slice(0, 7) }
const DIV: Record<string, string> = { ...DIVISIONS, NONE: '공통' }

export function Settlement() {
  const s = useSession()
  const [month, setMonth] = useState(kstMonth(-1))
  const period = month.replace('-', '')
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const d = useApi<any>(`/api/stl/settlement?period=${period}${storeId ? `&storeId=${storeId}` : ''}`, [s.storeId, period])
  const [accts, setAccts] = useState<any>(null)
  const [assets, setAssets] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const data = d.data
  const one = data?.stores?.length === 1 ? data.stores[0] : null
  const v = one ?? data?.total
  const [view, setView] = useState<'month' | 'week'>('month')
  const act = async (p: string, ok: string) => { setErr(null); try { await api(`/api/stl/settlement/${p}`, { body: { storeId, period } }); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) } }
  const row = (label: string, val: any, opt: { strong?: boolean; minus?: boolean; sub?: string } = {}) => (
    <tr className={opt.strong ? 'strong-row' : ''}><td>{opt.minus ? '− ' : ''}{label}{opt.sub && <small className="muted"> {opt.sub}</small>}</td><td className="num">{val == null ? <span className="muted">권한 없음</span> : won(val)}</td>
      <td className="num muted">{v?.net_sales && val != null ? `${(val / v.net_sales * 100).toFixed(1)}%` : ''}</td></tr>)
  if (s.currentStore?.site_type === 'HQ') return <HqSettlement month={month} setMonth={setMonth} />
  if (view === 'week') return <WeekView month={month} setMonth={setMonth} storeId={storeId} name={s.currentStore?.name ?? '볼 수 있는 매장 합산'} setView={setView} />
  return (
    <Page title="결산" sub={<>판매 수익 기준 월 결산 · 금액은 공급가(VAT 제외) {one && (one.closed ? <><Badge tone="ok">마감</Badge>{one.version > 1 && <> <Badge tone="info">수정 결산 v{one.version}</Badge></>}</> : <Badge tone="warn">진행 중{one.projected ? ' · 로열티·감가상각 예상치 포함' : ''}</Badge>)}</>}
      actions={<>
        <div className="seg"><button className="on">월 결산</button><button onClick={() => setView('week')}>주 마감</button></div>
        <input id="stl-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
        {one && storeId && s.has('OWNER', 'HQ_ADMIN') && <button onClick={async () => setAccts(await api(`/api/stl/settlement/accounts?storeId=${storeId}&period=${period}`))}>계정별 보기</button>}
        {storeId && s.has('OWNER', 'HQ_ADMIN') && <button onClick={() => setAssets(true)}>설비·비품</button>}
        {one && storeId && s.has('HQ_ADMIN') && (one.closed ? <button onClick={() => act('reopen', '마감을 취소했습니다')}>마감 취소</button>
          : <button className="primary" onClick={() => act('close', '마감했습니다 (로열티·감가상각 반영)')}>월 마감</button>)}
      </>}>
      <ErrorBox error={d.error || err} />
      {v && <>
        <div className="grid3">
          <div className="card stat"><small>순매출</small><b className="tnum">{won(v.net_sales)}원</b></div>
          <div className="card stat"><small>영업이익</small><b className="tnum">{won(v.operating_profit)}원</b></div>
          <div className="card stat"><small>결산액</small><b className="tnum">{v.settlement_amount == null ? '권한 없음' : won(v.settlement_amount) + '원'}</b><span className="muted small">영업이익 − 로열티 ± 영업외</span></div>
        </div>
        <div className="grid2">
          <section className="card table-wrap">
            <h2>손익 {one ? '' : `(합산 ${data.stores.length}개 매장)`}</h2>
            <table className="tbl pnl"><tbody>
              {row('순매출', v.net_sales, { strong: true })}
              {row('매출원가', v.cogs, { minus: true, sub: `기초 ${won(v.opening_stock)} + 매입 ${won(v.purchases)} − 기말 ${won(v.closing_stock)} 중 판매 소진·직접매입` })}
              {row('매출총이익', v.gross_profit, { strong: true })}
              {row('인건비', v.labor, { minus: true })}
              {row('임대료', v.rent, { minus: true })}
              {row('공과금', v.utility, { minus: true })}
              {row('내부사용(소모품)', v.internal_use, { minus: true })}
              {row('운영비', v.operating_exp, { minus: true })}
              {row('손실·결손', v.stock_loss, { minus: true })}
              {row('감가상각', v.depreciation, { minus: true })}
              {row('영업이익', v.operating_profit, { strong: true })}
              {row('로열티', v.royalty, { minus: true })}
              {row('영업외', v.non_operating)}
              {row('결산액', v.settlement_amount, { strong: true })}
            </tbody></table>
          </section>
          <section className="card">
            <h2>지표</h2>
            <div className="kv">
              {[['원가율', 'cogs'], ['인건비율', 'labor'], ['임대료율', 'rent'], ['손실률', 'loss'], ['결산 이익률', 'margin']].map(([l, k]) =>
                <div key={k}><small>{l}</small><b>{v.ratios?.[k] == null ? '—' : `${v.ratios[k]}%`}</b></div>)}
            </div>
            {one?.by_division && <><h2 style={{ marginTop: 16 }}>구분별</h2>
              <table className="tbl compact"><thead><tr><th>구분</th><th className="num">매출</th><th className="num">매입(재고)</th><th className="num">원가</th><th className="num">소모품</th><th className="num">손실·결손</th></tr></thead>
                <tbody>{Object.entries(one.by_division).filter(([k]) => k !== 'NONE').sort(([a], [b]) => Object.keys(DIV).indexOf(a) - Object.keys(DIV).indexOf(b)).map(([k, x]: any) => <tr key={k}><td>{DIV[k] ?? k}</td><td className="num">{won(x.sales)}</td><td className="num">{won(x.purchases)}</td><td className="num">{won(x.cogs)}</td><td className="num">{won(x.supplies ?? 0)}</td><td className="num">{won(x.loss)}</td></tr>)}</tbody></table></>}
            {!one && data?.stores && <><h2 style={{ marginTop: 16 }}>매장별</h2>
              <table className="tbl compact"><thead><tr><th>매장</th><th className="num">순매출</th><th className="num">영업이익</th><th className="num">결산액</th><th>상태</th></tr></thead>
                <tbody>{data.stores.map((x: any) => <tr key={x.store_id}><td>{s.stores.find(st => st.id === x.store_id)?.name}</td><td className="num">{won(x.net_sales)}</td><td className="num">{won(x.operating_profit)}</td><td className="num">{x.settlement_amount == null ? '—' : won(x.settlement_amount)}</td><td>{x.closed ? <Badge tone="ok">마감</Badge> : <Badge>진행</Badge>}</td></tr>)}</tbody></table></>}
          </section>
        </div>
        {one?.closed && storeId && s.has('OWNER', 'HQ_ADMIN') && <PostClose storeId={storeId} period={period} canEdit={s.has('HQ_ADMIN')} onChanged={(m: string) => { setMsg(m); d.reload() }} />}
        <p className="muted small">매출원가는 음료·디저트를 월말 재고 실사에서 '판매 소진'으로 반영한 금액과 외부 직접 매입분입니다. 식기·포장·관리 구분의 소진은 소모품비로 잡힙니다. 레시피 기반 자동 차감은 4차 범위입니다.</p>
      </>}
      {accts && <Modal title="계정별 합계" onClose={() => setAccts(null)} wide>
        <table className="tbl compact"><thead><tr><th>코드</th><th>계정</th><th className="num">차변</th><th className="num">대변</th></tr></thead>
          <tbody>{accts.map((a: any) => <tr key={a.code}><td><code>{a.code}</code></td><td>{a.name}</td><td className="num">{won(a.debit)}</td><td className="num">{won(a.credit)}</td></tr>)}</tbody></table>
      </Modal>}
      {assets && <AssetsModal storeId={storeId} storeName={s.currentStore?.name} onClose={() => setAssets(false)} onChanged={(m: string) => { setMsg(m); d.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

const SRC: Record<string, string> = { SALES_DAILY: '일 매출', LOGISTICS_RECEIPT: '물류 입고', STOCK_ADJ: '재고 조정', PAYROLL: '급여', EXPENSE: '비용', ROYALTY: '로열티', DEPRECIATION: '감가상각', COGS_CLOSE: '재고 실사', CLOSE_ADJ: '직접 조정' }
const KIND: Record<string, string> = { LATE: '발생 건 반영', ADJUST: '직접 조정', RECALC: '다시 계산' }
const COUNTERS: [string, string][] = [['103', '보통예금'], ['101', '현금'], ['253', '미지급금(본사)'], ['251', '외상매입금'], ['146', '상품(재고)'], ['108', '외상매출금(카드)']]

/** 마감 후 처리 (수정 결산): 마감 뒤 들어온 이 달 일자 처리 건 반영 · 직접 조정 · 버전 이력. 마감 상태는 유지 */
function PostClose({ storeId, period, canEdit, onChanged }: any) {
  const v = useApi<any>(`/api/stl/settlement/post-close?storeId=${storeId}&period=${period}`, [storeId, period])
  const [pull, setPull] = useState<any>(null)
  const [adj, setAdj] = useState(false)
  const [recalc, setRecalc] = useState(false)
  const done = (m: string) => { setPull(null); setAdj(false); setRecalc(false); v.reload(); onChanged(m) }
  const x = v.data
  const ym = `${period.slice(0, 4)}-${period.slice(4)}`
  return (
    <section className="card" id="post-close">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>마감 후 처리 <small className="muted">수정 결산</small></h2>
        {canEdit && <div className="row"><button onClick={() => setAdj(true)}>+ 조정 입력</button><button className="ghost" onClick={() => setRecalc(true)}>다시 계산</button></div>}
      </div>
      <p className="muted small">마감한 뒤에 이 달 날짜로 들어온 비용·급여·물류 입고 등은 자동으로 <b>이번 달</b>에 조정 분개로 들어갑니다. 이 달 결산에 넣어야 하면 [이 달에 반영]을 누르세요. 반영하거나 조정하면 결산을 다시 계산해 새 버전으로 저장하고, 로열티가 달라지면 차액을 본사 청구(다음 정산서)에 추가합니다. 마감 상태는 그대로입니다.</p>
      <ErrorBox error={v.error} />
      {x && <>
        <h3>마감 후 발생 건 ({x.late.length})</h3>
        {x.late.length === 0 ? <p className="muted small">마감 뒤 이 달 날짜로 들어온 처리 건이 없습니다.</p> :
          <div className="table-wrap"><table className="tbl compact">
            <thead><tr><th>원래 일자</th><th>구분</th><th>내용</th><th>손익 계정</th><th className="num">손익 금액</th><th>지금 반영된 달</th><th></th></tr></thead>
            <tbody>{x.late.map((e: any) => <tr key={e.id}>
              <td>{e.orig_date}</td><td>{SRC[e.source_type] ?? e.source_type}</td><td>{e.description}</td><td><small>{e.pnl_accounts ?? '—'}</small></td>
              <td className="num">{Number(e.pnl_amount) ? won(e.pnl_amount) : <small className="muted">손익 없음 ({won(e.amount)})</small>}</td><td>{e.current_period.slice(0, 4)}-{e.current_period.slice(4)}</td>
              <td>{canEdit && <button className="small primary" onClick={() => setPull(e)}>이 달에 반영</button>}</td></tr>)}</tbody>
          </table></div>}
        <h3>처리 이력</h3>
        {x.adjustments.length === 0 ? <p className="muted small">아직 마감 후 처리가 없습니다.</p> :
          <div className="table-wrap"><table className="tbl compact">
            <thead><tr><th>일시</th><th>처리</th><th>내용</th><th className="num">손익 금액</th><th className="num">로열티 차액</th><th>사유</th><th>버전</th></tr></thead>
            <tbody>{x.adjustments.map((a: any) => <tr key={a.id}>
              <td><small>{dt(a.created_at)}</small></td><td>{KIND[a.kind] ?? a.kind}</td>
              <td>{a.description}{a.counter_name && <small className="muted"> · 상대 {a.counter_name}</small>}</td>
              <td className="num">{a.amount ? won(a.amount) : '—'}</td><td className="num">{a.royalty_diff ? won(a.royalty_diff) : '—'}</td><td>{a.memo}</td><td>v{a.version}</td></tr>)}</tbody>
          </table></div>}
        {x.versions.length > 1 && <>
          <h3>결산 버전</h3>
          <div className="table-wrap"><table className="tbl compact">
            <thead><tr><th>버전</th><th>계산 일시</th><th className="num">순매출</th><th className="num">영업이익</th><th className="num">로열티</th><th className="num">결산액</th><th>사유</th></tr></thead>
            <tbody>{x.versions.map((r: any, i: number) => <tr key={r.version} className={i === 0 ? 'strong-row' : ''}>
              <td>v{r.version}{i === 0 && ' (현재)'}</td><td><small>{dt(r.calculated_at)}</small></td>
              <td className="num">{won(r.net_sales)}</td><td className="num">{won(r.operating_profit)}</td><td className="num">{won(r.royalty)}</td><td className="num">{won(r.settlement_amount)}</td><td>{r.revise_memo ?? <span className="muted">월 마감</span>}</td></tr>)}</tbody>
          </table></div></>}
      </>}
      {pull && <MemoModal title={`${ym} 결산에 반영`} intro={<>{pull.orig_date} · {SRC[pull.source_type] ?? pull.source_type} · {pull.description} · 손익 {won(Number(pull.pnl_amount) || 0)}원<br /><small className="muted">지금 들어가 있는 {pull.current_period.slice(0, 4)}-{pull.current_period.slice(4)}에서는 역분개로 빠지고, 원래 일자로 {ym}에 기록됩니다.</small></>}
        onClose={() => setPull(null)} submit={(memo: string) => api('/api/stl/settlement/post-close/pull', { body: { storeId, period, entryId: pull.id, memo } }).then((r: any) => done(`${ym} 결산에 반영했습니다 (수정 결산 v${r.version})`))} />}
      {recalc && <MemoModal title={`${ym} 결산 다시 계산`} intro={<small className="muted">로열티 정책을 소급해 바꿨을 때처럼, 분개는 그대로인데 결산값을 다시 계산해야 할 때 씁니다.</small>}
        onClose={() => setRecalc(false)} submit={(memo: string) => api('/api/stl/settlement/post-close/recalc', { body: { storeId, period, memo } }).then((r: any) => done(`다시 계산했습니다 (수정 결산 v${r.version})`))} />}
      {adj && <AdjustModal storeId={storeId} period={period} onClose={() => setAdj(false)} onDone={(r: any) => done(`조정을 반영했습니다 (수정 결산 v${r.version})`)} />}
    </section>
  )
}

function MemoModal({ title, intro, onClose, submit }: any) {
  const [memo, setMemo] = useState('')
  const [err, setErr] = useState<string | null>(null)
  return (
    <Modal title={title} onClose={onClose}>
      <form className="form" onSubmit={async e => { e.preventDefault(); setErr(null); try { await submit(memo) } catch (x: any) { setErr(x.message) } }}>
        <p className="small">{intro}</p>
        <label htmlFor="pc-memo">처리 사유</label>
        <input id="pc-memo" required value={memo} placeholder="예: 10월분 전기요금 고지서 11월 3일 수령" onChange={e => setMemo(e.target.value)} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary">반영</button>
      </form>
    </Modal>
  )
}

function AdjustModal({ storeId, period, onClose, onDone }: any) {
  const accts = useApi<any[]>('/api/stl/accounts')
  const ym = `${period.slice(0, 4)}-${period.slice(4)}`
  const last = new Date(Number(period.slice(0, 4)), Number(period.slice(4)), 0).getDate()
  const [f, setF] = useState({ accountCode: '', sign: '+', amount: '', counterCode: '103', date: `${ym}-${String(last).padStart(2, '0')}`, memo: '' })
  const [err, setErr] = useState<string | null>(null)
  const pnl = (accts.data ?? []).filter(a => a.pnl_section && a.is_active)
  const acc = pnl.find(a => a.code === f.accountCode)
  const save = async () => {
    setErr(null)
    try {
      const r = await api('/api/stl/settlement/post-close/adjust', { body: { storeId, period, accountCode: f.accountCode, amount: Number(f.amount) * (f.sign === '-' ? -1 : 1), counterCode: f.counterCode, date: f.date, memo: f.memo } })
      onDone(r)
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`마감 후 조정 · ${ym}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <label htmlFor="adj-acc">손익 계정</label>
        <select id="adj-acc" required value={f.accountCode} onChange={e => setF({ ...f, accountCode: e.target.value })}>
          <option value="">선택</option>
          {pnl.map(a => <option key={a.code} value={a.code}>{a.code} {a.name}</option>)}
        </select>
        <div className="row">
          <div><label htmlFor="adj-sign">증감</label><select id="adj-sign" value={f.sign} onChange={e => setF({ ...f, sign: e.target.value })}><option value="+">늘림 (+)</option><option value="-">줄임 (−)</option></select></div>
          <div><label htmlFor="adj-amt">금액 (원, 공급가)</label><input id="adj-amt" required inputMode="numeric" value={num(f.amount)} onChange={e => setF({ ...f, amount: e.target.value.replace(/\D/g, '') })} /></div>
        </div>
        <div className="row">
          <div><label htmlFor="adj-ctr">상대 계정 (돈이 오간 곳)</label><select id="adj-ctr" value={f.counterCode} onChange={e => setF({ ...f, counterCode: e.target.value })}>{COUNTERS.map(([c, n]) => <option key={c} value={c}>{c} {n}</option>)}</select></div>
          <div><label htmlFor="adj-date">일자 ({ym} 안)</label><input id="adj-date" type="date" required min={`${ym}-01`} max={`${ym}-${String(last).padStart(2, '0')}`} value={f.date} onChange={e => setF({ ...f, date: e.target.value })} /></div>
        </div>
        <label htmlFor="adj-memo">처리 사유</label>
        <input id="adj-memo" required value={f.memo} placeholder="예: 10월 카드 수수료 정산분 추가 청구" onChange={e => setF({ ...f, memo: e.target.value })} />
        {acc && f.amount && <p className="muted small">{acc.name} {f.sign === '-' ? '−' : '+'}{num(f.amount)}원 → 결산액 {(acc.type === 'REVENUE') === (f.sign === '+') ? '증가' : '감소'}. 매출 계정이 바뀌면 로열티 차액도 계산됩니다.</p>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary">조정 반영</button>
      </form>
    </Modal>
  )
}

/** 설비·비품 자산: 설비 카테고리 + 기준 단가 이상 고가 식기. 파손·폐기 → 장부가액만큼 처분손실 */
function AssetsModal({ storeId, storeName, onClose, onChanged }: any) {
  const list = useApi<any[]>(`/api/stl/assets?storeId=${storeId}`, [storeId])
  const [target, setTarget] = useState<any>(null)
  const [reason, setReason] = useState('파손')
  const [err, setErr] = useState<string | null>(null)
  const dispose = async () => {
    setErr(null)
    try {
      const r: any = await api(`/api/stl/assets/${target.id}/dispose`, { body: { reason } })
      setTarget(null); list.reload(); onChanged(`폐기 처리했습니다 (처분손실 ${won(r.bookValue)}원)`)
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`설비·비품 자산 · ${storeName ?? ''}`} onClose={onClose} wide>
      <p className="small muted">설비(가구·냉장고·머신 등)와 기준 단가 이상 고가 식기(비품)는 자산으로 5년 정액 감가상각합니다. 파손·폐기하면 남은 장부가액이 유형자산처분손실(영업외)로 잡힙니다. 일반 식기 파손은 재고 화면의 손실(식기 파손)로 처리하세요.</p>
      <ErrorBox error={list.error || err} />
      <div className="table-wrap"><table className="tbl compact">
        <thead><tr><th>자산</th><th>구분</th><th>취득일</th><th className="num">취득가</th><th className="num">상각누계</th><th className="num">장부가액</th><th></th></tr></thead>
        <tbody>{(list.data ?? []).map(a => <tr key={a.id} className={a.disposed_date ? 'inactive' : ''}>
          <td>{a.name}{a.disposed_date && <><br /><small className="muted">{a.disposed_date} 폐기 · {a.dispose_reason}</small></>}</td>
          <td><small>{DIVISIONS[a.division_code] ?? a.division_code}{a.division_code === 'TABLEWARE' ? ' · 비품' : ' · 설비'}</small></td>
          <td>{a.acquired_date}</td><td className="num">{won(a.cost)}</td><td className="num">{won(a.accumulated)}</td><td className="num">{a.disposed_date ? '—' : won(a.book_value)}</td>
          <td>{!a.disposed_date && <button className="ghost small" onClick={() => { setTarget(a); setReason('파손') }}>폐기</button>}</td>
        </tr>)}</tbody>
      </table></div>
      {list.data && list.data.length === 0 && <p className="muted small">등록된 자산이 없습니다.</p>}
      {target && <div className="note warn" style={{ marginTop: 12 }}>
        <b>{target.name}</b> 폐기 · 처분손실 {won(target.book_value)}원
        <div className="row" style={{ marginTop: 8 }}><input id="asset-reason" aria-label="폐기 사유" value={reason} onChange={e => setReason(e.target.value)} />
          <button className="primary" onClick={dispose}>폐기 확정</button><button className="ghost" onClick={() => setTarget(null)}>취소</button></div>
      </div>}
    </Modal>
  )
}

/** 주 마감: 월요일 시작 ~ 일요일, 일요일 24시 자동 확정 (분개를 잠그지 않는 주간 실적) */
function WeekView({ month, setMonth, storeId, name, setView }: any) {
  const d = useApi<any>(`/api/stl/settlement/weeks?month=${month}${storeId ? `&storeId=${storeId}` : ''}`, [month, storeId])
  const ST: Record<string, any> = { OPEN: ['진행 중', 'warn'], CLOSED: ['주 마감', 'ok'], PENDING: ['마감 대기', 'info'] }
  const ws: any[] = d.data?.weeks ?? []
  return (
    <Page title="결산 · 주 마감" sub={`${name} · 월요일 시작, 매주 일요일 24시 자동 마감 · 금액은 공급가(VAT 제외)`}
      actions={<>
        <div className="seg"><button onClick={() => setView('month')}>월 결산</button><button className="on">주 마감</button></div>
        <input id="wk-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
      </>}>
      <ErrorBox error={d.error} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>주</th><th>상태</th><th className="num">순매출</th><th className="num">매출원가</th><th className="num">매출총이익</th><th className="num">인건비</th><th className="num">임대·관리비(일할)</th><th className="num">공과금</th><th className="num">운영·소모·손실</th><th className="num">감가상각(일할)</th><th className="num">영업이익</th><th>마감 시각</th></tr></thead>
          <tbody>{ws.map(w => (
            <tr key={w.week_start}>
              <td><b>{w.week_start.slice(5)} ~ {w.week_end.slice(5)}</b></td>
              <td><Badge tone={ST[w.status][1]}>{ST[w.status][0]}</Badge></td>
              <td className="num">{won(w.net_sales)}</td><td className="num">{won(w.cogs)}</td><td className="num">{won(w.gross_profit)}</td>
              <td className="num">{won(w.labor)}</td><td className="num">{won(w.rent)}</td><td className="num">{won(w.utility)}</td>
              <td className="num">{won((w.operating_exp ?? 0) + (w.internal_use ?? 0) + (w.stock_loss ?? 0))}</td><td className="num">{won(w.depreciation)}</td>
              <td className="num"><b>{won(w.operating_profit)}</b></td>
              <td><small>{w.closed_at ? new Date(w.closed_at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '—'}</small></td>
            </tr>))}</tbody>
        </table>
      </div>
      <p className="muted small">주 마감은 그 주의 분개(매출·물류 입고·비용·급여 등 발생일 기준)를 모은 확정 실적입니다. <b>임대료·관리비·감가상각은 고정비라 월 금액을 날짜별로 나눠(일할) 그 주 일수만큼 넣습니다</b> (임대 계약 기준). 급여는 확정된 날이 속한 주에, 로열티는 월 마감에서 계산합니다.</p>
    </Page>
  )
}

/** 본사 매출 = 로열티 합계 + 물류 이득(매장 공급가 − 본사 출고 원가) */
function HqSettlement({ month, setMonth }: any) {
  const period = month.replace('-', '')
  const d = useApi<any>(`/api/stl/hq/summary?period=${period}`, [period])
  const v = d.data
  return (
    <Page title="결산 · 본사" sub="본사 매출 = 로열티 합계 + 물류 이득 · 금액은 공급가(VAT 제외)"
      actions={<input id="hq-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />}>
      <ErrorBox error={d.error} />
      {v && <>
        <div className="grid3">
          <div className="card stat"><small>본사 매출</small><b className="tnum">{won(v.hq_sales)}원</b><span className="muted small">로열티 + 물류 이득</span></div>
          <div className="card stat"><small>로열티 합계</small><b className="tnum">{won(v.royalty)}원</b><span className="muted small">{v.stores.length}개 매장 (마감 전 달은 예상치)</span></div>
          <div className="card stat"><small>물류 이득</small><b className="tnum">{won(v.logistics_margin)}원</b><span className="muted small">공급 {won(v.logistics_revenue)} − 원가 {won(v.logistics_cost)} · {v.requests}건</span></div>
        </div>
        <div className="grid2">
          <section className="card table-wrap"><h2>매장별 로열티</h2>
            <table className="tbl"><thead><tr><th>매장</th><th className="num">순매출</th><th className="num">로열티</th><th>상태</th></tr></thead>
              <tbody>{v.stores.map((x: any) => <tr key={x.store_id}><td>{x.store_name}</td><td className="num">{won(x.net_sales)}</td><td className="num">{won(x.royalty)}</td>
                <td>{x.closed ? <Badge tone="ok">마감</Badge> : <Badge tone="warn">예상</Badge>}</td></tr>)}</tbody></table></section>
          <section className="card table-wrap"><h2>매장별 물류 공급 (본사 물류)</h2>
            <table className="tbl"><thead><tr><th>매장</th><th className="num">공급가</th></tr></thead>
              <tbody>{(v.logistics_by_store ?? []).map((x: any) => <tr key={x.store_id}><td>{x.store_name}</td><td className="num">{won(x.revenue)}</td></tr>)}</tbody></table>
            <p className="muted small">물류 원가는 본사 창고에서 발송할 때 빠진 재고의 이동평균 단가 합계입니다(무상 재발송 원가 포함).</p></section>
        </div>
      </>}
    </Page>
  )
}
