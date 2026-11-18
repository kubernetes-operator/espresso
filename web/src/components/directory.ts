import { useEffect, useState } from 'react'
import { api } from '../api'

// 사용자 이름 표시용 캐시 (/api/auth/directory)
let cache: Record<string, string> | null = null
let pending: Promise<Record<string, string>> | null = null

export function loadDirectory() {
  if (cache) return Promise.resolve(cache)
  if (!pending) pending = api<any[]>('/api/auth/directory').then(list => {
    const m: Record<string, string> = Object.fromEntries(list.map(u => [u.id, u.name]))
    cache = m
    return m
  }).catch(() => { pending = null; return {} as Record<string, string> })
  return pending
}

export function useNames() {
  const [names, setNames] = useState<Record<string, string>>(cache ?? {})
  useEffect(() => { loadDirectory().then(setNames) }, [])
  return (id?: string | null) => (id ? names[id] ?? '…' : '—')
}
