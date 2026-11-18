import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast } from '../components/ui'
import { useSession } from '../session'

export function AdminStores() {
  const s = useSession()
  const nav = useNavigate()
  const list = useApi<any[]>('/api/core/stores')
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const save = async (id: string, body: any, label: string) => {
    setErr(null)
    try { await api(`/api/core/stores/${id}`, { method: 'PUT', body }); setMsg(label); list.reload(); s.reloadStores() }
    catch (e: any) { setErr(e.message) }
  }

  return (
    <Page title="매장 관리" sub="총괄 관리자 전용" actions={<button className="primary" onClick={() => nav('/admin/stores/new')}>+ 매장 생성</button>}>
      <ErrorBox error={list.error || err} />
      <div className="table-wrap card">
        <table className="tbl">
          <thead><tr><th>매장</th><th>권역</th><th>형태</th><th>상태</th><th>VAT 표시 기본값</th><th>점주 개인별 급여 열람</th><th>매장관리자 개인별 급여 열람</th><th>5인 이상 (가산)</th></tr></thead>
          <tbody>
            {(list.data ?? []).map(st => (
              <tr key={st.id}>
                <td><b>{st.name}</b> <code>{st.code}</code></td>
                <td>{st.region_name}</td>
                <td>{st.business_type === 'DIRECT' ? '직영' : '가맹'}</td>
                <td><Badge tone={st.status === 'OPEN' ? 'ok' : 'warn'}>{st.status}</Badge></td>
                <td>
                  <select id={`vat-${st.id}`} aria-label="VAT 표시 기본값" value={st.vat_display_mode}
                    onChange={e => save(st.id, { vatDisplayMode: e.target.value }, `${st.name} VAT 표시를 바꿨습니다`)}>
                    <option value="EXCLUSIVE">VAT 별도</option><option value="INCLUSIVE">VAT 포함</option>
                  </select>
                </td>
                <td>
                  <label className="switch"><input type="checkbox" checked={st.owner_payroll_detail_visible}
                    onChange={e => save(st.id, { ownerPayrollDetailVisible: e.target.checked }, `${st.name} 점주 급여 열람 설정을 바꿨습니다`)} />
                    <span>{st.owner_payroll_detail_visible ? '허용 (개인별)' : '미허용 (총액만)'}</span></label>
                </td>
                <td>
                  <label className="switch"><input type="checkbox" checked={st.manager_payroll_detail_visible}
                    onChange={e => save(st.id, { managerPayrollDetailVisible: e.target.checked }, `${st.name} 매장관리자 급여 열람 설정을 바꿨습니다`)} />
                    <span>{st.manager_payroll_detail_visible ? '허용' : '본인만'}</span></label>
                </td>
                <td>
                  <label className="switch"><input type="checkbox" checked={st.five_plus}
                    onChange={e => save(st.id, { fivePlus: e.target.checked }, `${st.name} 5인 이상 여부를 바꿨습니다`)} />
                    <span>{st.five_plus ? '적용' : '미적용'}</span></label>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
