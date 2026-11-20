import { useMemo, useState } from 'react'
import { api } from '../api'
import { prepareUpload, fmtSize, UPLOAD_LIMIT } from '../upload'
import { useApi, Page, Badge, ErrorBox, Modal, Toast, Empty } from '../components/ui'
import { useSession } from '../session'
import { won } from '../format'

const thisMonth = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 7)

export function Sales() {
  const s = useSession()
  const [month, setMonth] = useState(thisMonth())
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const list = useApi<any[]>(`/api/core/sales?month=${month}${storeId ? `&storeId=${storeId}` : ''}`, [s.storeId, month])
  const [edit, setEdit] = useState<any>(null)
  const [upload, setUpload] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const inc = s.vatMode === 'INCLUSIVE'
  const rows = list.data ?? []
  const sum = useMemo(() => rows.reduce((t, r) => ({
    total: t.total + Number(r.total_amount), supply: t.supply + Number(r.gross_supply) + Number(r.exempt_supply) - Number(r.discount_amount) - Number(r.refund_amount),
    customers: t.customers + Number(r.customer_count ?? 0) }), { total: 0, supply: 0, customers: 0 }), [rows])
  const canEdit = !!storeId && s.has('HQ_ADMIN', 'OWNER', 'STORE_MGR')
  const act = async (path: string, ok: string) => { setErr(null); try { await api(path, { body: {} }); setMsg(ok); list.reload() } catch (e: any) { setErr(e.message) } }

  return (
    <Page title="매출" sub={`${s.currentStore?.name ?? '전체 매장'} · 입력은 POS 마감과 같은 결제 금액(VAT 포함) 기준, 저장은 공급가·세액 분리`}
      actions={<>
        <input id="sales-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
        {canEdit && <button onClick={() => setUpload(true)}>엑셀 업로드</button>}
        {canEdit && <button className="primary" onClick={() => setEdit({ sales_date: new Date(Date.now() + 9 * 3600e3 - 864e5).toISOString().slice(0, 10) })}>+ 일 매출</button>}
      </>}>
      <ErrorBox error={list.error || err} />
      {!storeId && <div className="note warn">입력은 상단에서 매장을 하나 고른 뒤 할 수 있습니다.</div>}
      {storeId && <p className="muted small">직원들이 근무 마감에서 입력한 금액은 <a href="/closing">근무 마감</a> 화면의 소계로 모이고, 거기서 [일 매출로 반영]하면 이 표에 들어옵니다.</p>}
      <div className="grid3">
        <div className="card stat"><small>순매출 ({inc ? 'VAT 포함' : 'VAT 별도'})</small><b className="tnum">{won(inc ? sum.total : sum.supply)}원</b><span className="muted small">{rows.length}일</span></div>
        <div className="card stat"><small>일평균</small><b className="tnum">{won(rows.length ? (inc ? sum.total : sum.supply) / new Set(rows.map(r => r.sales_date)).size : 0)}원</b></div>
        <div className="card stat"><small>객단가</small><b className="tnum">{won(sum.customers ? sum.total / sum.customers : 0)}원</b><span className="muted small">고객 {won(sum.customers)}명</span></div>
      </div>
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>일자</th>{!storeId && <th>매장</th>}<th className="num">음료</th><th className="num">디저트</th><th className="num">식기·포장</th><th className="num">기타</th><th className="num">할인·환불</th><th className="num">순매출</th><th className="num">세액</th><th className="num">카드/현금</th><th className="num">고객</th><th>상태</th><th></th></tr></thead>
          <tbody>{rows.map(r => {
            const d = r.division_totals ? JSON.parse(r.division_totals) : {}
            return (
              <tr key={r.id}>
                <td>{r.sales_date}</td>{!storeId && <td>{r.store_name}</td>}
                <td className="num">{won(d.BEVERAGE)}</td><td className="num">{won(d.DESSERT)}</td><td className="num">{won(d.TABLEWARE)}</td><td className="num">{won(d.MANAGEMENT)}</td>
                <td className="num">{won(Number(r.discount_amount) + Number(r.refund_amount))}</td>
                <td className="num"><b>{won(inc ? r.total_amount : Number(r.total_amount) - Number(r.vat_amount))}</b></td>
                <td className="num">{won(r.vat_amount)}</td>
                <td className="num"><small>{won(r.card_amount)} / {won(r.cash_amount)}</small></td>
                <td className="num">{r.customer_count ?? '—'}</td>
                <td><Badge tone={r.status === 'CLOSED' ? 'ok' : 'warn'}>{r.status === 'CLOSED' ? '마감' : '입력 중'}</Badge> {r.source !== 'MANUAL' && <Badge>{r.source === 'EXCEL' ? '엑셀' : 'POS'}</Badge>}</td>
                <td className="nowrap">
                  {r.status === 'OPEN' && canEdit && <><button className="ghost small" onClick={() => setEdit(r)}>수정</button><button className="small" onClick={() => act(`/api/core/sales/${r.id}/close`, '마감했습니다')}>마감</button></>}
                  {r.status === 'CLOSED' && s.has('HQ_ADMIN') && <button className="ghost small" onClick={() => act(`/api/core/sales/${r.id}/reopen`, '마감을 취소했습니다')}>마감 취소</button>}
                </td>
              </tr>)
          })}</tbody>
        </table>
        {rows.length === 0 && <Empty>이 달 매출이 없습니다.</Empty>}
      </div>
      {edit && <SalesModal storeId={storeId} row={edit} onClose={() => setEdit(null)} onDone={(m: string) => { setEdit(null); setMsg(m); list.reload() }} />}
      {upload && <UploadModal storeId={storeId} onClose={() => setUpload(false)} onDone={(m: string) => { setUpload(false); setMsg(m); list.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function SalesModal({ storeId, row, onClose, onDone }: any) {
  const d = row.division_totals ? JSON.parse(row.division_totals) : {}
  const [f, setF] = useState<Record<string, string>>({
    date: row.sales_date, beverage: String(d.BEVERAGE ?? ''), dessert: String(d.DESSERT ?? ''), tableware: String(d.TABLEWARE ?? ''), etc: String(d.MANAGEMENT ?? ''),
    discount: String(row.discount_amount ? Math.round(row.discount_amount * 1.1) : ''), refund: '', card: '', cash: String(row.cash_amount ?? ''), customers: String(row.customer_count ?? ''),
  })
  const [err, setErr] = useState<string | null>(null)
  const n = (k: string) => Number(f[k] || 0)
  const net = n('beverage') + n('dessert') + n('tableware') + n('etc') - n('discount') - n('refund')
  const save = async (close: boolean) => {
    setErr(null)
    try {
      await api(`/api/core/sales/${storeId}/${f.date}`, { method: 'PUT', body: {
        beverage: n('beverage'), dessert: n('dessert'), tableware: n('tableware'), etc: n('etc'), discount: n('discount'), refund: n('refund'),
        card: f.card ? n('card') : net - n('cash'), cash: n('cash'), otherPay: 0, customers: f.customers ? n('customers') : null, close } })
      onDone(close ? '저장·마감했습니다' : '저장했습니다')
    } catch (e: any) { setErr(e.message) }
  }
  const field = (k: string, label: string) => (
    <div><label htmlFor={`sm-${k}`}>{label}</label><input id={`sm-${k}`} inputMode="numeric" value={f[k]} onChange={e => setF({ ...f, [k]: e.target.value.replace(/\D/g, '') })} /></div>)
  return (
    <Modal title="일 매출 입력" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save(false) }}>
        <label htmlFor="sm-date">일자</label><input id="sm-date" required type="date" value={f.date} onChange={e => setF({ ...f, date: e.target.value })} />
        <div className="row">{field('beverage', '음료')}{field('dessert', '디저트')}{field('tableware', '식기·포장')}{field('etc', '기타')}</div>
        <div className="row">{field('discount', '할인')}{field('refund', '환불')}</div>
        <div className="row">{field('cash', '현금')}{field('card', '카드 (비우면 나머지)')}{field('customers', '고객 수')}</div>
        <p className="small">실 매출(VAT 포함) <b>{won(net)}</b>원 · 공급가 {won(Math.round(net / 1.1))} · 세액 {won(net - Math.round(net / 1.1))}</p>
        {err && <div className="error-box">{err}</div>}
        <div className="act-buttons"><button>저장</button><button type="button" className="primary" onClick={() => save(true)}>저장 후 마감</button></div>
      </form>
    </Modal>
  )
}

function UploadModal({ storeId, onClose, onDone }: any) {
  const [file, setFile] = useState<File | null>(null)
  const [close, setClose] = useState(true)
  const [res, setRes] = useState<any>(null)
  const [err, setErr] = useState<string | null>(null)
  const go = async () => {
    if (!file) return
    setErr(null)
    try { const fd = new FormData(); fd.append('file', await prepareUpload(file)); const r: any = await api(`/api/core/sales/${storeId}/upload?close=${close}`, { body: fd }); setRes(r); if (!r.errors.length) onDone(`${r.applied}일 매출을 반영했습니다`) }
    catch (e: any) { setErr(e.message) }
  }
  const tpl = async () => {
    const r: Response = await api('/api/core/sales/template.csv', { raw: true })
    const url = URL.createObjectURL(await r.blob()); const a = document.createElement('a'); a.href = url; a.download = 'sales-template.csv'; a.click()
  }
  return (
    <Modal title="매출 엑셀 업로드" onClose={onClose}>
      <div className="form">
        <p className="small">열 순서: 일자, 음료, 디저트, 식기·포장, 기타, 면세, 할인, 환불, 카드, 현금, 기타결제, 고객수 (금액은 VAT 포함). .xlsx 또는 .csv · 머리글 행이 있으면 열 이름으로 읽습니다(식기·포장 열이 없는 예전 양식도 가능) · 파일당 최대 {fmtSize(UPLOAD_LIMIT)} (넘으면 기간을 나눠 올리기)</p>
        <button type="button" onClick={tpl}>양식 내려받기 (CSV)</button>
        <input id="up-file" type="file" accept=".xlsx,.xls,.csv" aria-label="파일" onChange={e => setFile(e.target.files?.[0] ?? null)} />
        <label className="switch"><input type="checkbox" checked={close} onChange={e => setClose(e.target.checked)} /><span>올리면서 바로 마감</span></label>
        {err && <div className="error-box">{err}</div>}
        {res?.errors?.length > 0 && <div className="error-box">{res.applied}일 반영, 오류 {res.errors.length}건<ul>{res.errors.slice(0, 8).map((e: string) => <li key={e}>{e}</li>)}</ul></div>}
        <button className="primary" disabled={!file} onClick={go}>업로드</button>
      </div>
    </Modal>
  )
}
