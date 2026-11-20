import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox } from '../components/ui'
import { useSession } from '../session'
import { won, DIVISIONS } from '../format'

type Item = { id: string; sku: string; name: string; unit: string; tax_type: string; list_price: number; base_price: number; discount_type: string | null; discount_value: number | null; division_code: string; category_name: string; is_asset: boolean }
type Line = { key: string; sourceType: 'HQ' | 'EXTERNAL'; itemId?: string; name: string; unit: string; qty: number; taxType: string
  listPrice: number; discountType?: string | null; discountValue?: number | null
  externalUrl?: string; vendorName?: string; categoryId?: string; unitPrice?: number; priceInputMode?: string; payer?: string; shippingFee?: number }

// 서버(PriceService/Vat)와 같은 규칙으로 미리보기 계산
function calc(l: Line) {
  if (l.sourceType === 'HQ') {
    const gross = Math.floor(l.listPrice * l.qty)
    let d = 0
    if (l.discountType === 'RATE' && l.discountValue) d = Math.floor(l.listPrice * l.qty * Number(l.discountValue))
    if (l.discountType === 'AMOUNT' && l.discountValue) d = Math.floor(Number(l.discountValue) * l.qty)
    const supply = Math.max(gross - d, 0)
    const vat = l.taxType === 'EXEMPT' ? 0 : Math.floor(supply / 10)
    return { discount: d, supply, vat, ship: 0 }
  }
  const gross = Math.floor((l.unitPrice ?? 0) * l.qty)
  let supply = gross, vat = 0
  if (l.taxType === 'EXEMPT') { supply = gross; vat = 0 }
  else if (l.priceInputMode === 'INCLUSIVE') { supply = Math.round(gross / 1.1); vat = gross - supply }
  else { vat = Math.floor(gross / 10) }
  return { discount: 0, supply, vat, ship: l.shippingFee ?? 0 }
}

export function LogisticsNew() {
  const s = useSession()
  const nav = useNavigate()
  // 상단에서 매장을 골랐으면 그 매장으로 고정, 전체(또는 본사)면 요청할 매장을 고름
  const reqSites = s.stores.filter(x => (x.site_type ?? 'STORE') !== 'HQ')
  const locked = s.storeId !== 'ALL' && reqSites.some(x => x.id === s.storeId)
  const [storeId, setStoreId] = useState(locked ? s.storeId : reqSites[0]?.id ?? '')
  const [tab, setTab] = useState<'HQ' | 'EXTERNAL'>('HQ')
  const [division, setDivision] = useState('')
  const [q, setQ] = useState('')
  const [title, setTitle] = useState('')
  const [desired, setDesired] = useState('')
  const [memo, setMemo] = useState('')
  const [lines, setLines] = useState<Line[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const items = useApi<Item[]>(storeId ? `/api/core/catalog/items?storeId=${storeId}${division ? `&division=${division}` : ''}` : null, [storeId, division])
  const cats = useApi<any[]>('/api/core/catalog/categories')
  const store = s.stores.find(x => x.id === storeId)
  const vatMode = (s.user?.vatOverride as any) || store?.vat_display_mode || 'EXCLUSIVE'
  const inc = vatMode === 'INCLUSIVE'

  const shown = useMemo(() => (items.data ?? []).filter(i => !q || i.name.includes(q) || i.sku.toLowerCase().includes(q.toLowerCase())), [items.data, q])
  const qtyOf = (id: string) => lines.find(l => l.itemId === id)?.qty ?? 0
  const setQty = (it: Item, qty: number) => {
    setLines(ls => {
      const rest = ls.filter(l => l.itemId !== it.id)
      if (qty <= 0) return rest
      return [...rest, { key: it.id, sourceType: 'HQ', itemId: it.id, name: it.name, unit: it.unit, qty, taxType: it.tax_type, listPrice: it.list_price, discountType: it.discount_type, discountValue: it.discount_value }]
    })
  }

  const [ext, setExt] = useState({ externalUrl: '', name: '', vendorName: '', categoryId: '', unitPrice: '', qty: '1', priceInputMode: 'INCLUSIVE', taxType: 'TAXABLE', payer: 'STORE', shippingFee: '0' })
  const addExt = () => {
    setErr(null)
    if (!/^https?:\/\//i.test(ext.externalUrl)) { setErr('http:// 또는 https:// 로 시작하는 주소를 넣으세요.'); return }
    if (!ext.name || !ext.categoryId || !ext.unitPrice) { setErr('품명, 카테고리, 단가를 입력하세요.'); return }
    setLines(ls => [...ls, { key: 'x' + Date.now() + Math.random(), sourceType: 'EXTERNAL', name: ext.name, unit: 'ea', qty: Number(ext.qty) || 1, taxType: ext.taxType, listPrice: 0,
      externalUrl: ext.externalUrl, vendorName: ext.vendorName, categoryId: ext.categoryId, unitPrice: Number(ext.unitPrice), priceInputMode: ext.priceInputMode, payer: ext.payer, shippingFee: Number(ext.shippingFee) || 0 }])
    setExt({ ...ext, externalUrl: '', name: '', vendorName: '', unitPrice: '', qty: '1', shippingFee: '0' })
  }

  const totals = lines.reduce((t, l) => {
    const c = calc(l)
    if (l.taxType === 'EXEMPT') t.exempt += c.supply; else t.taxable += c.supply
    t.vat += c.vat; t.ship += c.ship
    return t
  }, { taxable: 0, exempt: 0, vat: 0, ship: 0 })

  const save = async (submit: boolean) => {
    setErr(null); setBusy(true)
    try {
      const r = await api<any>('/api/core/logistics', { body: {
        storeId, title, desiredDate: desired || null, memo, submit,
        lines: lines.map(l => l.sourceType === 'HQ' ? { sourceType: 'HQ', itemId: l.itemId, qty: l.qty }
          : { sourceType: 'EXTERNAL', externalUrl: l.externalUrl, itemName: l.name, vendorName: l.vendorName, categoryId: l.categoryId, qty: l.qty,
              unitPrice: l.unitPrice, priceInputMode: l.priceInputMode, taxType: l.taxType, payer: l.payer, shippingFee: l.shippingFee }),
      } })
      nav(`/logistics/${r.id}`)
    } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }

  return (
    <Page title="물류 요청 작성" sub={<>매장별 단가·할인이 자동 적용됩니다 · <Badge tone={inc ? 'accent' : 'neutral'}>VAT {inc ? '포함' : '별도'} 표시</Badge></>}>
      <div className="card form-inline">
        <div><label htmlFor="ln-store">매장</label>
          {locked ? <b id="ln-store">{store?.name}</b> :
          <select id="ln-store" value={storeId} onChange={e => { setStoreId(e.target.value); setLines([]) }}>
            {reqSites.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>}</div>
        <div className="grow"><label htmlFor="ln-title">제목</label><input id="ln-title" value={title} onChange={e => setTitle(e.target.value)} placeholder="예: 11월 3주 원두·컵" /></div>
        <div><label htmlFor="ln-date">요청 기한</label><input id="ln-date" type="date" value={desired} min={new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10)} onChange={e => setDesired(e.target.value)} /></div>
      </div>
      <div className="split">
        <section className="card">
          <div className="seg" role="tablist">
            <button className={tab === 'HQ' ? 'on' : ''} onClick={() => setTab('HQ')}>본사 물류</button>
            <button className={tab === 'EXTERNAL' ? 'on' : ''} onClick={() => setTab('EXTERNAL')}>외부 구매 (URL)</button>
          </div>
          {tab === 'HQ' ? (<>
            <div className="filters">
              <div className="seg small">
                {[['', '전체'], ...Object.entries(DIVISIONS)].map(([k, v]) =>
                  <button key={k} className={division === k ? 'on' : ''} onClick={() => setDivision(k)}>{v}</button>)}
              </div>
              <input id="ln-q" placeholder="품명·코드 검색" value={q} onChange={e => setQ(e.target.value)} aria-label="품목 검색" />
            </div>
            <ErrorBox error={items.error} />
            <div className="item-list">
              {shown.map(it => {
                const qty = qtyOf(it.id)
                const disc = it.discount_type === 'RATE' ? `-${Math.round(Number(it.discount_value) * 100)}%` : it.discount_type === 'AMOUNT' ? `-${won(it.discount_value)}원` : ''
                return (
                  <div key={it.id} className={`item-row ${qty ? 'picked' : ''}`}>
                    <div className="item-info">
                      <b>{it.name}</b>
                      <small className="muted">{it.category_name} · {it.sku} {it.tax_type === 'EXEMPT' && <Badge>면세</Badge>} {it.is_asset && <Badge tone="info">설비</Badge>}</small>
                      <small>{it.list_price !== it.base_price && <s className="muted">{won(it.base_price)}</s>} <b className="tnum">{won(it.list_price)}</b>원/{it.unit} {disc && <Badge tone="ok">{disc}</Badge>}</small>
                    </div>
                    <div className="stepper">
                      <button aria-label="빼기" onClick={() => setQty(it, qty - 1)} disabled={!qty}>−</button>
                      <input aria-label={`${it.name} 수량`} inputMode="decimal" value={qty || ''} placeholder="0" onChange={e => setQty(it, Number(e.target.value) || 0)} />
                      <button aria-label="더하기" onClick={() => setQty(it, qty + 1)}>+</button>
                    </div>
                  </div>
                )
              })}
            </div>
          </>) : (
            <div className="form">
              <label htmlFor="ex-url">상품 주소 (URL)</label>
              <input id="ex-url" value={ext.externalUrl} onChange={e => setExt({ ...ext, externalUrl: e.target.value })} placeholder="https://" />
              <div className="row">
                <div><label htmlFor="ex-name">품명</label><input id="ex-name" value={ext.name} onChange={e => setExt({ ...ext, name: e.target.value })} /></div>
                <div><label htmlFor="ex-vendor">판매처</label><input id="ex-vendor" value={ext.vendorName} onChange={e => setExt({ ...ext, vendorName: e.target.value })} /></div>
              </div>
              <label htmlFor="ex-cat">카테고리</label>
              <select id="ex-cat" value={ext.categoryId} onChange={e => setExt({ ...ext, categoryId: e.target.value })}>
                <option value="">선택</option>
                {[...new Set((cats.data ?? []).map(c => c.division_name))].map(d => <optgroup key={d} label={d}>
                  {(cats.data ?? []).filter(c => c.division_name === d).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</optgroup>)}
              </select>
              <div className="row two">
                <div><label htmlFor="ex-price">단가 (원)</label><input id="ex-price" inputMode="numeric" value={ext.unitPrice} onChange={e => setExt({ ...ext, unitPrice: e.target.value.replace(/\D/g, '') })} /></div>
                <div><label htmlFor="ex-mode">단가 기준</label>
                  <select id="ex-mode" value={ext.priceInputMode} onChange={e => setExt({ ...ext, priceInputMode: e.target.value })}>
                    <option value="INCLUSIVE">VAT 포함 금액</option><option value="EXCLUSIVE">VAT 별도 금액</option></select></div>
              </div>
              <div className="row two">
                <div><label htmlFor="ex-tax">과세</label>
                  <select id="ex-tax" value={ext.taxType} onChange={e => setExt({ ...ext, taxType: e.target.value })}>
                    <option value="TAXABLE">과세</option><option value="EXEMPT">면세</option></select></div>
                <div><label htmlFor="ex-qty">수량</label><input id="ex-qty" inputMode="decimal" value={ext.qty} onChange={e => setExt({ ...ext, qty: e.target.value })} /></div>
              </div>
              <div className="row two">
                <div><label htmlFor="ex-ship">배송비 (원)</label><input id="ex-ship" inputMode="numeric" value={ext.shippingFee} onChange={e => setExt({ ...ext, shippingFee: e.target.value.replace(/\D/g, '') })} /></div>
                <div><label htmlFor="ex-payer">결제 주체</label>
                  <select id="ex-payer" value={ext.payer} onChange={e => setExt({ ...ext, payer: e.target.value })}>
                    <option value="STORE">매장 결제</option><option value="HQ">본사 결제 (정산서 청구)</option></select></div>
              </div>
              <p className="muted small">비용은 결제 주체와 관계없이 이 매장 금액으로 잡힙니다.</p>
              <button onClick={addExt}>요청 목록에 추가</button>
            </div>
          )}
        </section>

        <section className="card cart" id="cart">
          <h2>요청 목록 ({lines.length})</h2>
          {lines.length === 0 ? <div className="muted small">왼쪽에서 품목을 담으세요.</div> : (
            <div className="table-wrap">
              <table className="tbl compact">
                <thead><tr><th>품목</th><th className="num">수량</th><th className="num">할인</th><th className="num">공급가</th><th className="num">세액</th><th></th></tr></thead>
                <tbody>
                  {lines.map(l => { const c = calc(l); return (
                    <tr key={l.key}>
                      <td>{l.sourceType === 'EXTERNAL' && <Badge tone="accent">외부{l.payer === 'HQ' ? '·본사결제' : ''}</Badge>} {l.name}</td>
                      <td className="num">{l.qty}</td>
                      <td className="num">{c.discount ? `-${won(c.discount)}` : ''}</td>
                      <td className="num">{won(c.supply)}</td>
                      <td className="num">{l.taxType === 'EXEMPT' ? '0 (면세)' : won(c.vat)}</td>
                      <td><button className="ghost small" aria-label="삭제" onClick={() => setLines(ls => ls.filter(x => x.key !== l.key))}>✕</button></td>
                    </tr>) })}
                </tbody>
              </table>
            </div>
          )}
          <dl className="totals">
            <dt>과세 공급가</dt><dd>{won(totals.taxable)}</dd>
            <dt>면세 공급가</dt><dd>{won(totals.exempt)}</dd>
            <dt>세액</dt><dd>{won(totals.vat)}</dd>
            {totals.ship > 0 && <><dt>배송비</dt><dd>{won(totals.ship)}</dd></>}
            <dt className="strong">합계 ({inc ? 'VAT 포함' : 'VAT 별도'})</dt>
            <dd className="strong">{won(inc ? totals.taxable + totals.exempt + totals.vat + totals.ship : totals.taxable + totals.exempt + totals.ship)}</dd>
          </dl>
          <label htmlFor="ln-memo" className="small muted">메모</label>
          <textarea id="ln-memo" rows={2} value={memo} onChange={e => setMemo(e.target.value)} />
          <p className="muted small">기본 결재선: 매장관리자 → 점주 → 물류관리자 → 총괄 (요청자 등급 이하 단계는 자동 통과)</p>
          {err && <div className="error-box">{err}</div>}
          <div className="act-buttons">
            <button disabled={busy || !lines.length} onClick={() => save(false)}>임시저장</button>
            <button className="primary" disabled={busy || !lines.length} onClick={() => save(true)}>상신하기</button>
          </div>
        </section>
      </div>
      {lines.length > 0 && (
        <button className="cart-bar" onClick={() => document.getElementById('cart')?.scrollIntoView({ behavior: 'smooth' })}>
          요청 목록 {lines.length}개 · {won(inc ? totals.taxable + totals.exempt + totals.vat + totals.ship : totals.taxable + totals.exempt + totals.ship)}원 <b>확인·상신 ↓</b>
        </button>
      )}
    </Page>
  )
}
