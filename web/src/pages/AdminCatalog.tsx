import { useMemo, useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { won, DIVISIONS } from '../format'

export function AdminCatalog() {
  const s = useSession()
  const [storeId, setStoreId] = useState(s.stores[0]?.id ?? '')
  const [division, setDivision] = useState('')
  const [q, setQ] = useState('')
  const items = useApi<any[]>(`/api/core/catalog/items?activeOnly=false${storeId ? `&storeId=${storeId}` : ''}${division ? `&division=${division}` : ''}`, [storeId, division])
  const cats = useApi<any[]>('/api/core/catalog/categories')
  const [edit, setEdit] = useState<any>(null)
  const [price, setPrice] = useState<any>(null)
  const [thr, setThr] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const shown = useMemo(() => (items.data ?? []).filter(i => !q || i.name.includes(q) || i.sku.toLowerCase().includes(q.toLowerCase())), [items.data, q])
  const storeName = s.stores.find(x => x.id === storeId)?.name

  return (
    <Page title="품목·단가" sub="본사 기준가와 매장별 단가·할인 (요청 시점 금액으로 문서에 고정)"
      actions={<>
        <select id="cat-store" aria-label="매장" value={storeId} onChange={e => setStoreId(e.target.value)}>
          {s.stores.map(x => <option key={x.id} value={x.id}>{x.name} 단가 보기</option>)}
        </select>
        {s.has('HQ_ADMIN') && <button onClick={() => setThr(true)}>비품 기준</button>}
        <button className="primary" onClick={() => setEdit({})}>+ 품목</button>
      </>}>
      <div className="filters">
        <div className="seg small">
          {[['', '전체'], ...Object.entries(DIVISIONS)].map(([k, v]) =>
            <button key={k} className={division === k ? 'on' : ''} onClick={() => setDivision(k)}>{v}</button>)}
        </div>
        <input id="cat-q" aria-label="검색" placeholder="품명·코드 검색" value={q} onChange={e => setQ(e.target.value)} />
      </div>
      <ErrorBox error={items.error} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>코드</th><th>품명</th><th>구분 · 카테고리</th><th>과세</th><th className="num">본사 기준가</th><th className="num">{storeName} 단가</th><th>{storeName} 할인</th><th></th></tr></thead>
          <tbody>
            {shown.map(i => (
              <tr key={i.id} className={i.is_active ? '' : 'inactive'}>
                <td><code>{i.sku}</code></td>
                <td>{i.name} {i.is_asset && <Badge tone="info">{i.division_code === 'TABLEWARE' ? '비품' : '설비'}</Badge>} {!i.is_active && <Badge>중지</Badge>}</td>
                <td><small>{i.division_name} · {i.category_name}</small></td>
                <td>{i.tax_type === 'EXEMPT' ? <Badge>면세</Badge> : '과세'}</td>
                <td className="num">{won(i.base_price)}</td>
                <td className="num">{i.store_unit_price != null ? <b>{won(i.store_unit_price)}</b> : <span className="muted">기준가</span>}</td>
                <td>{i.discount_type === 'RATE' ? `${(Number(i.discount_value) * 100).toFixed(1)}%` : i.discount_type === 'AMOUNT' ? `${won(i.discount_value)}원/단위` : '—'}</td>
                <td className="nowrap"><button className="ghost small" onClick={() => setPrice(i)}>매장 단가</button><button className="ghost small" onClick={() => setEdit(i)}>수정</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && <ItemModal item={edit} cats={cats.data ?? []} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setMsg('저장했습니다'); items.reload() }} />}
      {price && <PriceModal item={price} storeId={storeId} storeName={storeName} onClose={() => setPrice(null)} onDone={() => { setPrice(null); setMsg('매장 단가를 바꿨습니다 (오늘부터 적용)'); items.reload() }} />}
      {thr && <ThresholdModal cats={cats.data ?? []} onClose={() => setThr(false)} onDone={(m: string) => { setThr(false); setMsg(m); cats.reload(); items.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

/** 카테고리별 비품 기준 단가: 기준가가 이 금액 이상인 품목은 비품(자산)으로 입고·감가상각 (예: 고가 식기) */
function ThresholdModal({ cats, onClose, onDone }: any) {
  const [all, setAll] = useState(false)
  const editable = cats.filter((c: any) => !c.is_asset)
  const shown = editable.filter((c: any) => all || c.division_code === 'TABLEWARE' || c.asset_threshold)
  const [vals, setVals] = useState<Record<string, string>>(Object.fromEntries(editable.map((c: any) => [c.id, c.asset_threshold ? String(c.asset_threshold) : ''])))
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try {
      for (const c of editable) {
        const v = vals[c.id] ? Number(vals[c.id]) : null
        if (v !== (c.asset_threshold == null ? null : Number(c.asset_threshold)))
          await api(`/api/core/catalog/categories/${c.id}/asset-threshold`, { method: 'PUT', body: { assetThreshold: v } })
      }
      onDone('비품 기준을 저장했습니다 (해당 품목 자산 여부 재계산)')
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="비품 기준 단가" onClose={onClose}>
      <div className="form">
        <p className="small muted">기준가(VAT 제외)가 이 금액 이상인 품목은 재고가 아니라 비품(자산)으로 입고되어 5년 정액 감가상각됩니다. 비우면 기준 없음. 설비 카테고리는 항상 자산입니다.</p>
        <div className="table-wrap" style={{ maxHeight: 360 }}><table className="tbl compact"><tbody>
          {shown.map((c: any) => <tr key={c.id}><td><small>{c.division_name}</small> · {c.name}</td>
            <td><input aria-label={`${c.name} 기준 단가`} inputMode="numeric" placeholder="없음" value={vals[c.id] ?? ''} onChange={e => setVals({ ...vals, [c.id]: e.target.value.replace(/\D/g, '') })} /></td></tr>)}
        </tbody></table></div>
        <label className="switch"><input type="checkbox" checked={all} onChange={e => setAll(e.target.checked)} /><span>다른 카테고리도 보기</span></label>
        {err && <div className="error-box">{err}</div>}
        <button className="primary" onClick={save}>저장</button>
      </div>
    </Modal>
  )
}

function ItemModal({ item, cats, onClose, onDone }: any) {
  const isNew = !item.id
  const [f, setF] = useState({ sku: item.sku ?? '', name: item.name ?? '', categoryId: item.category_id ?? '', unit: item.unit ?? 'ea', taxType: item.tax_type ?? 'TAXABLE', basePrice: String(item.base_price ?? ''), isActive: item.is_active ?? true })
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try {
      const body = { ...f, basePrice: Number(f.basePrice) }
      if (isNew) await api('/api/core/catalog/items', { body }); else await api(`/api/core/catalog/items/${item.id}`, { method: 'PUT', body })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={isNew ? '품목 추가' : `품목 수정 · ${item.sku}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        {isNew && <><label htmlFor="it-sku">코드</label><input id="it-sku" value={f.sku} onChange={e => setF({ ...f, sku: e.target.value })} /></>}
        <label htmlFor="it-name">품명</label><input id="it-name" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} />
        <label htmlFor="it-cat">카테고리</label>
        <select id="it-cat" value={f.categoryId} onChange={e => setF({ ...f, categoryId: e.target.value })}>
          <option value="">선택</option>{cats.map((c: any) => <option key={c.id} value={c.id}>{c.division_name} · {c.name}{c.is_asset ? ' (설비)' : ''}</option>)}
        </select>
        <div className="row">
          <div><label htmlFor="it-unit">단위</label><input id="it-unit" value={f.unit} onChange={e => setF({ ...f, unit: e.target.value })} /></div>
          <div><label htmlFor="it-tax">과세</label><select id="it-tax" value={f.taxType} onChange={e => setF({ ...f, taxType: e.target.value })}><option value="TAXABLE">과세</option><option value="EXEMPT">면세</option></select></div>
          <div><label htmlFor="it-price">본사 기준가 (공급가)</label><input id="it-price" inputMode="numeric" value={f.basePrice} onChange={e => setF({ ...f, basePrice: e.target.value.replace(/\D/g, '') })} /></div>
        </div>
        {!isNew && <label className="switch"><input type="checkbox" checked={f.isActive} onChange={e => setF({ ...f, isActive: e.target.checked })} /><span>요청 가능</span></label>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}

function PriceModal({ item, storeId, storeName, onClose, onDone }: any) {
  const [unitPrice, setUnitPrice] = useState(item.store_unit_price != null ? String(item.store_unit_price) : '')
  const [dType, setDType] = useState(item.discount_type ?? '')
  const [dVal, setDVal] = useState(item.discount_type === 'RATE' ? String(Number(item.discount_value) * 100) : item.discount_value != null ? String(item.discount_value) : '')
  const [err, setErr] = useState<string | null>(null)
  const save = async (clear = false) => {
    setErr(null)
    try {
      await api('/api/core/catalog/store-prices', { body: clear ? { storeId, itemId: item.id } : {
        storeId, itemId: item.id, unitPrice: unitPrice ? Number(unitPrice) : null,
        discountType: dType || null, discountValue: dType === 'RATE' ? Number(dVal) / 100 : dType === 'AMOUNT' ? Number(dVal) : null } })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`${storeName} 단가 · ${item.name}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <p className="muted small">본사 기준가 {won(item.base_price)}원. 비워 두면 기준가를 씁니다.</p>
        <label htmlFor="sp-p">매장 단가 (공급가)</label><input id="sp-p" inputMode="numeric" value={unitPrice} onChange={e => setUnitPrice(e.target.value.replace(/\D/g, ''))} placeholder={String(item.base_price)} />
        <div className="row">
          <div><label htmlFor="sp-t">할인</label><select id="sp-t" value={dType} onChange={e => setDType(e.target.value)}><option value="">없음</option><option value="RATE">비율 (%)</option><option value="AMOUNT">단위당 금액 (원)</option></select></div>
          {dType && <div><label htmlFor="sp-v">{dType === 'RATE' ? '할인율 %' : '할인액 원'}</label><input id="sp-v" inputMode="decimal" value={dVal} onChange={e => setDVal(e.target.value)} /></div>}
        </div>
        {err && <div className="error-box">{err}</div>}
        <div className="act-buttons"><button type="button" onClick={() => save(true)}>매장 설정 지우기</button><button className="primary">저장</button></div>
      </form>
    </Modal>
  )
}
