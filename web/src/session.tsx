import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, getToken, setToken } from './api'
import type { VatMode } from './format'

export type Assignment = { r: string; t: 'ALL' | 'REGION' | 'STORE'; s: string | null }
export type User = { id: string; loginId: string; name: string; assignments: Assignment[]; vatOverride: string; initialPassword?: boolean }
export type Store = {
  id: string; code: string; name: string; status: string; region_id: string; region_name: string; site_type?: 'STORE' | 'HQ' | 'BAKERY'
  vat_display_mode: VatMode; owner_payroll_detail_visible: boolean; manager_payroll_detail_visible: boolean
}

type Session = {
  user: User | null
  roles: Set<string>
  has: (...roles: string[]) => boolean
  stores: Store[]
  storeId: string | 'ALL'            // 선택 매장 (2개 이상이면 '전체' 가능)
  setStoreId: (id: string | 'ALL') => void
  currentStore: Store | null
  vatMode: VatMode                    // 계정 지정값 → 매장 기본값
  login: (loginId: string, password: string) => Promise<void>
  logout: () => void
  reloadStores: () => Promise<void>
  passwordChanged: () => void
  ready: boolean
}

const Ctx = createContext<Session>(null as any)
export const useSession = () => useContext(Ctx)

const STORE_KEY = 'espresso.store'

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [stores, setStores] = useState<Store[]>([])
  const [storeId, setStoreIdState] = useState<string | 'ALL'>('ALL')
  const [ready, setReady] = useState(false)

  const reloadStores = useCallback(async () => {
    const list = await api<Store[]>('/api/core/stores').catch(() => [])
    setStores(list)
    let saved: string | null = null
    try { saved = localStorage.getItem(STORE_KEY) } catch { /* ignore */ }
    if (saved && (saved === 'ALL' ? list.length > 1 : list.some(s => s.id === saved))) setStoreIdState(saved)
    else {
      // 기본 선택: 첫 매장 (본사·베이커리보다 매장 우선)
      const first = list.find(x => (x.site_type ?? 'STORE') === 'STORE') ?? list[0]
      setStoreIdState(first ? first.id : 'ALL')
    }
  }, [])

  const loadMe = useCallback(async () => {
    if (!getToken()) { setReady(true); return }
    try {
      const me = await api<any>('/api/auth/me')
      setUser({ id: me.id, loginId: me.loginId, name: me.name, vatOverride: me.vatOverride, initialPassword: me.initialPassword,
        assignments: me.assignments.map((a: any) => ({ r: a.role, t: a.scopeType, s: a.scopeId })) })
      await reloadStores()
    } catch { setToken(null); setUser(null) }
    setReady(true)
  }, [reloadStores])

  useEffect(() => { loadMe() }, [loadMe])
  useEffect(() => {
    const h = () => { setUser(null); setStores([]) }
    window.addEventListener('espresso:logout', h)
    return () => window.removeEventListener('espresso:logout', h)
  }, [])

  const login = async (loginId: string, password: string) => {
    const r = await api<any>('/api/auth/login', { body: { loginId, password } })
    setToken(r.accessToken)
    try { localStorage.removeItem(STORE_KEY) } catch { /* ignore */ }
    setUser(r.user)
    await reloadStores()
  }
  const logout = () => { setToken(null); setUser(null); setStores([]) }

  const setStoreId = (id: string | 'ALL') => {
    setStoreIdState(id)
    try { localStorage.setItem(STORE_KEY, id) } catch { /* ignore */ }
  }

  const value = useMemo<Session>(() => {
    const roles = new Set((user?.assignments ?? []).map(a => a.r))
    const currentStore = storeId === 'ALL' ? null : stores.find(s => s.id === storeId) ?? null
    const vatMode: VatMode = (user?.vatOverride as VatMode) || currentStore?.vat_display_mode || 'EXCLUSIVE'
    return {
      user, roles, has: (...r: string[]) => r.some(x => roles.has(x)), stores, storeId, setStoreId,
      currentStore, vatMode, login, logout, reloadStores, ready,
      passwordChanged: () => setUser(u => u ? { ...u, initialPassword: false } : u),
    }
  }, [user, stores, storeId, ready, reloadStores])

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
