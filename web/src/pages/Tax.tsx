import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast } from '../components/ui'
import { won, dt } from '../format'

export function Tax() {
  const now = new Date(Date.now() + 9 * 3600e3)
  const [year, setYear] = useState(now.getUTCFullYear())
  const [q, setQ] = useState(Math.floor(now.getUTCMonth() / 3) + 1)
  const vat = useApi<any[]>(`/api/stl/tax/vat-summary?year=${year}&quarter=${q}`, [year, q])
  const inv = useApi<any[]>('/api/stl/tax/invoices')
  const [from, setFrom] = useState(`${year}-${String((q - 1) * 3 + 1).padStart(2, '0')}`)
  const [to, setTo] = useState(now.toISOString().slice(0, 7))
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const download = async () => {
    setErr(null)
    try {
      const r: Response = await api(`/api/stl/tax/export/journal.csv?from=${from.replace('-', '')}&to=${to.replace('-', '')}`, { raw: true })
      const url = URL.createObjectURL(await r.blob()); const a = document.createElement('a'); a.href = url; a.download = `journal-${from}-${to}.csv`; a.click()
      setMsg('전표를 내려받았습니다')
    } catch (e: any) { setErr(e.message) }
  }
  const setNo = async (id: string) => {
    const no = window.prompt('국세청 승인번호를 입력하세요')
    if (!no) return
    try { await api(`/api/stl/tax/invoices/${id}/approval-no`, { body: { approvalNo: no } }); inv.reload() } catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="세무" sub="회계 프로그램 전표 내보내기 · 분기 부가세 집계 · 전자세금계산서">
      <ErrorBox error={vat.error || err} />
      <section className="card form-inline">
        <div><label htmlFor="tx-from">전표 시작 월</label><input id="tx-from" type="month" value={from} onChange={e => setFrom(e.target.value)} /></div>
        <div><label htmlFor="tx-to">끝 월</label><input id="tx-to" type="month" value={to} onChange={e => setTo(e.target.value)} /></div>
        <button className="primary" onClick={download}>전표 내려받기 (더존 업로드용 CSV)</button>
        <p className="muted small grow">계정과목의 외부 코드(더존 코드)를 넣어 두면 함께 나갑니다.</p>
      </section>
      <section className="card table-wrap">
        <div className="form-inline" style={{ padding: 12 }}>
          <div><label htmlFor="tx-y">연도</label><input id="tx-y" type="number" value={year} onChange={e => setYear(Number(e.target.value))} style={{ width: 100 }} /></div>
          <div><label htmlFor="tx-q">분기</label><select id="tx-q" value={q} onChange={e => setQ(Number(e.target.value))}>{[1, 2, 3, 4].map(x => <option key={x} value={x}>{x}분기</option>)}</select></div>
        </div>
        <table className="tbl">
          <thead><tr><th>매장</th><th className="num">과세 매출(공급가)</th><th className="num">매출세액</th><th className="num">매입세액</th><th className="num">납부(환급) 예상</th></tr></thead>
          <tbody>{(vat.data ?? []).map(r => <tr key={r.store_id}><td>{r.store_name}</td><td className="num">{won(r.sales_supply)}</td><td className="num">{won(r.output_vat)}</td><td className="num">{won(r.input_vat)}</td><td className="num"><b>{won(r.payable)}</b></td></tr>)}</tbody>
        </table>
      </section>
      <section className="card table-wrap">
        <h2 style={{ padding: '12px 12px 0' }}>전자세금계산서</h2>
        <p className="muted small" style={{ padding: '0 12px' }}>발행 대행사가 아직 연동되지 않아 '대기'로 쌓입니다. 홈택스 등에서 발행한 뒤 승인번호를 넣으면 '발행'으로 바뀝니다.</p>
        <table className="tbl">
          <thead><tr><th>구분</th><th>공급받는자</th><th>작성일</th><th className="num">공급가</th><th className="num">세액</th><th>상태</th><th>승인번호</th><th></th></tr></thead>
          <tbody>{(inv.data ?? []).map(r => <tr key={r.id}><td>{r.direction === 'ISSUE' ? '발행' : '수취'}</td><td>{r.buyer_name}</td><td>{r.write_date}</td>
            <td className="num">{won(r.supply_amount)}</td><td className="num">{won(r.vat_amount)}</td><td><Badge tone={r.status === 'ISSUED' ? 'ok' : 'warn'}>{r.status === 'ISSUED' ? '발행' : '대기'}</Badge></td>
            <td><code>{r.nts_approval_no ?? '—'}</code></td><td>{r.status === 'PENDING' && <button className="small" onClick={() => setNo(r.id)}>승인번호 입력</button>}</td></tr>)}</tbody>
        </table>
      </section>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
