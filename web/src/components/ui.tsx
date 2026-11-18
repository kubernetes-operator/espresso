import { useEffect, useState, type ReactNode } from 'react'
import { api } from '../api'

export function useApi<T>(path: string | null, deps: any[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!path) { setData(null); return }
    let alive = true
    setLoading(true); setError(null)
    api<T>(path).then(d => { if (alive) setData(d) }).catch(e => { if (alive) setError(e.message) }).finally(() => alive && setLoading(false))
    return () => { alive = false }
  }, [path, tick, ...deps])
  return { data, error, loading, reload: () => setTick(t => t + 1), setData }
}

export function Page({ title, actions, children, sub }: { title: string; sub?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>{title}</h1>{sub && <div className="sub">{sub}</div>}</div>
        {actions && <div className="actions">{actions}</div>}
      </div>
      {children}
    </div>
  )
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'ok' | 'warn' | 'bad' | 'info' | 'accent'; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null
  return <div className="error-box" role="alert">{error}</div>
}

export function Toast({ msg, onDone }: { msg: string | null; onDone: () => void }) {
  useEffect(() => { if (msg) { const t = setTimeout(onDone, 2600); return () => clearTimeout(t) } }, [msg])
  if (!msg) return null
  return <div className="toast" role="status">{msg}</div>
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return (
    <div className="modal-back" onClick={onClose}>
      <div className={`modal ${wide ? 'wide' : ''}`} onClick={e => e.stopPropagation()} role="dialog" aria-label={title}>
        <div className="modal-head"><h2>{title}</h2><button className="ghost" onClick={onClose} aria-label="닫기">✕</button></div>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  )
}

/** 준비 중인 기능 안내 (PLAN.md 단계) */
export function Planned({ title, stage, items }: { title: string; stage: number; items: string[] }) {
  return (
    <Page title={title} sub={<Badge tone="info">구현 단계 {stage} 예정</Badge>}>
      <div className="card">
        <p>이 화면은 구현 계획 {stage}단계에서 추가됩니다. 들어갈 기능:</p>
        <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>
      </div>
    </Page>
  )
}
