// API 호출 공통: 토큰 첨부, 오류 메시지 통일
const TOKEN_KEY = 'espresso.token'

export function getToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY) } catch { return null }
}
export function setToken(t: string | null) {
  try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY) } catch { /* ignore */ }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

export async function api<T = any>(path: string, opts: { method?: string; body?: any; raw?: boolean } = {}): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  let body: BodyInit | undefined
  if (opts.body instanceof FormData) body = opts.body
  else if (opts.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.body) }
  const res = await fetch(path, { method: opts.method ?? (body ? 'POST' : 'GET'), headers, body })
  if (res.status === 401 && token) {
    setToken(null)
    window.dispatchEvent(new Event('espresso:logout'))
  }
  if (!res.ok) {
    let code = 'ERROR', message = `요청이 실패했습니다 (${res.status})`
    try { const j = await res.json(); code = j.code ?? code; message = j.message ?? message } catch { /* ignore */ }
    throw new ApiError(res.status, code, message)
  }
  if (opts.raw) return res as any
  const text = await res.text()
  return (text ? JSON.parse(text) : null) as T
}
