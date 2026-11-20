import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { api } from '../api'
import { dt } from '../format'
import { useSession } from '../session'
import { visibleMenu, menuFor, type MenuItem } from '../menu'
import { ROLE_NAMES, SITE_TYPES } from '../format'

const TABS: { key: NonNullable<MenuItem['mobileTab']>; label: string; icon: string }[] = [
  { key: 'home', label: '홈', icon: '⌂' },
  { key: 'request', label: '요청', icon: '+' },
  { key: 'approve', label: '결재', icon: '✓' },
  { key: 'check', label: '확인', icon: '▦' },
  { key: 'more', label: '더보기', icon: '⋯' },
]

export function AppShell() {
  const s = useSession()
  const nav = useNavigate()
  const loc = useLocation()
  const menu = visibleMenu(s.has)
  const [sheet, setSheet] = useState<string | null>(null)
  const [pending, setPending] = useState(0)
  const [noti, setNoti] = useState<{ items: any[]; unread: number }>({ items: [], unread: 0 })
  const [notiOpen, setNotiOpen] = useState(false)
  useEffect(() => {
    let alive = true
    const load = () => {
      api<any>('/api/core/approvals/count').then(r => alive && setPending(r.pending)).catch(() => {})
      api<any>('/api/core/notifications').then(r => alive && setNoti(r)).catch(() => {})
    }
    load()
    const t = setInterval(load, 30000)
    return () => { alive = false; clearInterval(t) }
  }, [loc.pathname])
  const groups = [...new Set(menu.map(m => m.group))]
  const cur = menuFor(loc.pathname)
  const [helpOpen, setHelpOpen] = useState(false)
  const siteGroups = (['HQ', 'STORE', 'BAKERY'] as const).map(t => ({ t, list: s.stores.filter(x => (x.site_type ?? 'STORE') === t) })).filter(g => g.list.length)
  const roleLabel = [...s.roles].map(r => ROLE_NAMES[r] ?? r).join(' · ')

  return (
    <div className="shell">
      <aside className="side" aria-label="주 메뉴">
        <div className="brand"><span className="brand-name">Cafe de Lyon</span><span className="brand-short" aria-hidden>CdL</span></div>
        <nav>
          {groups.map(g => (
            <div key={g} className="nav-group">
              <div className="nav-group-label">{g}</div>
              {menu.filter(m => m.group === g).map(m => (
                <NavLink key={m.path} to={m.path} end={m.path === '/'} className="nav-item" title={`${m.label} — ${m.desc}`}>
                  <span className="nav-icon" aria-hidden>{m.icon}</span><span className="nav-label">{m.label}</span>
                  {m.path === '/approvals' && pending > 0 && <span className="count">{pending}</span>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="store-picker">
            {s.stores.length > 1 ? (
              <select id="store-select" aria-label="매장 선택" value={s.storeId} onChange={e => s.setStoreId(e.target.value)}>
                {siteGroups.map(g => <optgroup key={g.t} label={SITE_TYPES[g.t]}>
                  {g.list.map(st => <option key={st.id} value={st.id}>{st.name}</option>)}
                </optgroup>)}
                <option value="ALL">{cur && cur.all === false ? '전체 (이 화면은 매장 선택 필요)' : '전체 (합산)'}</option>
              </select>
            ) : s.stores.length === 1 ? <strong>{s.stores[0].name}</strong> : <strong>{s.has('INVESTOR') ? '투자자' : '본사'}</strong>}
            <span className={`badge ${s.vatMode === 'INCLUSIVE' ? 'accent' : 'neutral'}`} title="계정 지정값이 있으면 그 값을, 없으면 매장 기본값을 따릅니다">
              VAT {s.vatMode === 'INCLUSIVE' ? '포함' : '별도'}
            </span>
          </div>
          <div className="who">
            <div className="noti">
              <button className="ghost" aria-label="알림" onClick={() => { setNotiOpen(o => !o); if (noti.unread) api('/api/core/notifications/read-all', { body: {} }).catch(() => {}) }}>
                🔔{noti.unread > 0 && <span className="count">{noti.unread}</span>}
              </button>
              {notiOpen && (
                <div className="noti-pop" onClick={() => setNotiOpen(false)}>
                  {noti.items.length === 0 && <div className="muted small">알림이 없습니다.</div>}
                  {noti.items.slice(0, 12).map(n => (
                    <button key={n.id} className={`noti-item ${n.read_at ? '' : 'unread'}`} onClick={() => n.link && nav(n.link)}>
                      <b>{n.title}</b><span className="small">{n.body}</span><small className="muted">{dt(n.created_at)}</small>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="who-text"><b>{s.user?.name}</b><small>{roleLabel}</small></div>
            <button className="ghost small" onClick={() => { s.logout(); nav('/login') }}>로그아웃</button>
          </div>
        </header>
        {s.user?.initialPassword && loc.pathname !== '/' && (
          <div className="pw-banner">초기 비밀번호를 쓰고 있습니다. <button className="ghost small" onClick={() => nav('/')}>홈에서 비밀번호 바꾸기</button></div>
        )}
        {cur && (
          <div className="page-help">
            <span><b>{cur.label}</b> · {cur.desc}</span>
            {cur.tips?.length ? <button className="ghost small" aria-expanded={helpOpen} onClick={() => setHelpOpen(o => !o)}>{helpOpen ? '도움말 닫기' : '사용법'}</button> : null}
            {helpOpen && cur.tips && <ul>{cur.tips.map(t => <li key={t}>{t}</li>)}</ul>}
          </div>
        )}
        <main className="content" key={loc.pathname + s.storeId}>
          <Outlet />
        </main>
      </div>

      {/* 모바일 하단 탭 */}
      <nav className="tabbar" aria-label="하단 탭">
        {TABS.map(t => {
          const items = menu.filter(m => m.mobileTab === t.key)
          if (!items.length) return null
          const active = items.some(i => (i.path === '/' ? loc.pathname === '/' : loc.pathname.startsWith(i.path)))
          return (
            <button key={t.key} className={`tab ${active ? 'active' : ''}`}
              onClick={() => items.length === 1 && t.key !== 'more' ? nav(items[0].path) : setSheet(t.key)}>
              <span aria-hidden>{t.icon}</span><small>{t.label}</small>
              {t.key === 'approve' && pending > 0 && <span className="count tab-count">{pending}</span>}
            </button>
          )
        })}
      </nav>
      {sheet && (
        <div className="sheet-back" onClick={() => setSheet(null)}>
          <div className="sheet" onClick={e => e.stopPropagation()}>
            {(sheet === 'more' ? menu.filter(m => m.mobileTab === 'more' || !m.mobileTab) : menu.filter(m => m.mobileTab === sheet)).map(m => (
              <button key={m.path} className="sheet-item" onClick={() => { setSheet(null); nav(m.path) }}>
                <span aria-hidden>{m.icon}</span>{m.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
