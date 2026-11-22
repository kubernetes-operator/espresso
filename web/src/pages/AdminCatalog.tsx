import { useMemo, useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { won, DIVISIONS, num } from '../format'

export function AdminCatalog() {
  const s = useSession()
  // 상단 매장 선택을 따름. 전체·본사·베이커리를 고르면 본사 단가만 표시
  const storeId = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') === 'STORE' ? s.storeId : ''
  const hqView = s.has('HQ_ADMIN', 'LOGISTICS_MGR')
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
  const [prefix, setPrefix] = useState(false)

  return (
    <Page title="품목·단가" sub={storeId ? `본사 기준가·본사 매입가와 ${storeName} 단가·할인 (상단에서 매장을 바꾸면 그 매장 단가)` : '본사 단가 (본사 기준가 = 매장 공급가, 본사 매입가 = 본사가 사 오는 값) · 매장 단가는 상단에서 매장을 고르세요'}
      actions={<>
        <button onClick={() => setPrefix(true)}>코드 규칙</button>
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
          <thead><tr><th>코드</th><th>품명</th><th>구분 · 카테고리</th><th>과세</th>{hqView && <th className="num">본사 매입가</th>}<th className="num">본사 기준가</th>{hqView && <th className="num">본사 마진</th>}
            {storeId && <><th className="num">{storeName} 단가</th><th>{storeName} 할인</th></>}<th></th></tr></thead>
          <tbody>
            {shown.map(i => (
              <tr key={i.id} className={i.is_active ? '' : 'inactive'}>
                <td><code>{i.sku}</code></td>
                <td>{i.name} {i.is_asset && <Badge tone="info">{i.division_code === 'TABLEWARE' ? '비품' : '설비'}</Badge>} {!i.is_active && <Badge>중지</Badge>}</td>
                <td><small>{i.division_name} · {i.category_name}{i.net_weight_g != null ? ` · ${Number(i.net_weight_g).toLocaleString('ko-KR')}g` : i.consumption_group ? ' · 중량 미입력' : ''}</small></td>
                <td>{i.tax_type === 'EXEMPT' ? <Badge>면세</Badge> : '과세'}</td>
                {hqView && <td className="num">{i.hq_cost != null ? won(i.hq_cost) : <span className="muted">—</span>}</td>}
                <td className="num">{won(i.base_price)}</td>
                {hqView && <td className="num"><small>{i.hq_cost != null && i.base_price ? `${Math.round((i.base_price - i.hq_cost) / i.base_price * 1000) / 10}%` : ''}</small></td>}
                {storeId && <>
                  <td className="num">{i.store_unit_price != null ? <b>{won(i.store_unit_price)}</b> : <span className="muted">기준가</span>}</td>
                  <td>{i.discount_type === 'RATE' ? `${(Number(i.discount_value) * 100).toFixed(1)}%` : i.discount_type === 'AMOUNT' ? `${won(i.discount_value)}원/단위` : '—'}</td></>}
                <td className="nowrap">{storeId && <button className="ghost small" onClick={() => setPrice(i)}>매장 단가</button>}<button className="ghost small" onClick={() => setEdit(i)}>수정</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {edit && <ItemModal item={edit} cats={cats.data ?? []} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setMsg('저장했습니다'); items.reload() }} />}
      {price && <PriceModal item={price} storeId={storeId} storeName={storeName} onClose={() => setPrice(null)} onDone={() => { setPrice(null); setMsg('매장 단가를 바꿨습니다 (오늘부터 적용)'); items.reload() }} />}
      {prefix && <PrefixModal cats={cats.data ?? []} onClose={() => setPrefix(false)} onDone={() => { setPrefix(false); setMsg('코드 접두어를 저장했습니다'); cats.reload() }} />}
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
            <td><input aria-label={`${c.name} 기준 단가`} inputMode="numeric" placeholder="없음" value={num(vals[c.id] ?? '')} onChange={e => setVals({ ...vals, [c.id]: e.target.value.replace(/\D/g, '') })} /></td></tr>)}
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
  const [f, setF] = useState({ sku: item.sku ?? '', name: item.name ?? '', categoryId: item.category_id ?? '', unit: item.unit ?? 'ea', taxType: item.tax_type ?? 'TAXABLE',
    basePrice: String(item.base_price ?? ''), hqCost: item.hq_cost != null ? String(item.hq_cost) : '', isActive: item.is_active ?? true,
    netWeightG: item.net_weight_g != null ? String(Number(item.net_weight_g)) : '' })
  const [auto, setAuto] = useState(isNew)
  const [err, setErr] = useState<string | null>(null)
  // 카테고리를 고르면 코드 자동 제안 (접두어-연번). 직접 바꾸면 자동 제안을 멈춤
  const pickCat = async (categoryId: string) => {
    setF(x => ({ ...x, categoryId }))
    if (!auto || !categoryId) return
    try { const r: any = await api(`/api/core/catalog/next-sku?categoryId=${categoryId}`); setF(x => ({ ...x, categoryId, sku: r.sku })) } catch { /* ignore */ }
  }
  const save = async () => {
    setErr(null)
    try {
      const body = { ...f, basePrice: Number(f.basePrice), hqCost: f.hqCost === '' ? (isNew ? null : -1) : Number(f.hqCost), netWeightG: f.netWeightG === '' ? (isNew ? null : 0) : Number(f.netWeightG) }
      if (isNew) await api('/api/core/catalog/items', { body }); else await api(`/api/core/catalog/items/${item.id}`, { method: 'PUT', body })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  const divisions = [...new Set(cats.map((c: any) => c.division_name))]
  return (
    <Modal title={isNew ? '품목 추가' : `품목 수정 · ${item.sku}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <label htmlFor="it-cat">구분 · 카테고리</label>
        <select id="it-cat" required value={f.categoryId} onChange={e => pickCat(e.target.value)}>
          <option value="">선택</option>
          {divisions.map((d: any) => <optgroup key={d} label={d}>{cats.filter((c: any) => c.division_name === d).map((c: any) =>
            <option key={c.id} value={c.id}>{c.name} ({c.sku_prefix}){c.is_asset ? ' · 설비' : ''}</option>)}</optgroup>)}
        </select>
        <label htmlFor="it-sku">품목 코드 {isNew && <small className="muted">— 카테고리 접두어로 자동 생성, 직접 바꿀 수 있음</small>}</label>
        <div className="row">
          <input id="it-sku" value={f.sku} placeholder={isNew ? '카테고리를 고르면 자동' : ''} onChange={e => { setAuto(false); setF({ ...f, sku: e.target.value.toUpperCase() }) }} />
          {isNew && f.categoryId && <button type="button" className="ghost small" onClick={() => { setAuto(true); pickCat(f.categoryId) }}>자동 코드 다시 받기</button>}
        </div>
        <label htmlFor="it-name">품명</label><input id="it-name" required value={f.name} onChange={e => setF({ ...f, name: e.target.value })} />
        <div className="row">
          <div><label htmlFor="it-unit">단위</label><input id="it-unit" required value={f.unit} onChange={e => setF({ ...f, unit: e.target.value })} /></div>
          <div><label htmlFor="it-wt">단위당 중량 (g){item.consumption_group && <small className="muted"> · 재고 소비 분석에 사용</small>}</label><input id="it-wt" inputMode="decimal" value={f.netWeightG} placeholder="예: 원두 1kg → 1000" onChange={e => setF({ ...f, netWeightG: e.target.value.replace(/[^\d.]/g, '') })} /></div>
          <div><label htmlFor="it-tax">과세</label><select id="it-tax" value={f.taxType} onChange={e => setF({ ...f, taxType: e.target.value })}><option value="TAXABLE">과세</option><option value="EXEMPT">면세</option></select></div>
        </div>
        <div className="row">
          <div><label htmlFor="it-cost">본사 매입가 (공급가)</label><input id="it-cost" inputMode="numeric" value={num(f.hqCost)} placeholder="비공개 · 물류 관리자 이상" onChange={e => setF({ ...f, hqCost: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="it-price">본사 기준가 (매장 공급가)</label><input id="it-price" required inputMode="numeric" value={num(f.basePrice)} onChange={e => setF({ ...f, basePrice: e.target.value.replace(/\D/g, '') })} /></div>
        </div>
        {!isNew && <label className="switch"><input type="checkbox" checked={f.isActive} onChange={e => setF({ ...f, isActive: e.target.checked })} /><span>요청 가능</span></label>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}

/** 카테고리별 품목 코드 접두어 (예: 커피 CF → CF-0001) */
function PrefixModal({ cats, onClose, onDone }: any) {
  const [vals, setVals] = useState<Record<string, string>>(Object.fromEntries(cats.map((c: any) => [c.id, c.sku_prefix ?? ''])))
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try {
      for (const c of cats) if ((vals[c.id] ?? '') !== (c.sku_prefix ?? ''))
        await api(`/api/core/catalog/categories/${c.id}/sku-prefix`, { method: 'PUT', body: { skuPrefix: vals[c.id] } })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="품목 코드 규칙" onClose={onClose}>
      <div className="form">
        <p className="small muted">새 품목 코드 = 카테고리 접두어 + 4자리 연번 (예: CF-0001). 기존 코드는 바뀌지 않습니다. 품목 추가 화면에서 제안된 코드를 직접 고칠 수도 있습니다.</p>
        <div className="table-wrap" style={{ maxHeight: 380 }}><table className="tbl compact"><tbody>
          {cats.map((c: any) => <tr key={c.id}><td><small>{c.division_name}</small> · {c.name}</td>
            <td><input aria-label={`${c.name} 접두어`} style={{ width: 90 }} value={vals[c.id] ?? ''} onChange={e => setVals({ ...vals, [c.id]: e.target.value.toUpperCase() })} /></td>
            <td><code>{(vals[c.id] || '??')}-0001</code></td></tr>)}
        </tbody></table></div>
        {err && <div className="error-box">{err}</div>}
        <button className="primary" onClick={save}>저장</button>
      </div>
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
        <label htmlFor="sp-p">매장 단가 (공급가)</label><input id="sp-p" inputMode="numeric" value={num(unitPrice)} onChange={e => setUnitPrice(e.target.value.replace(/\D/g, ''))} placeholder={String(item.base_price)} />
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
