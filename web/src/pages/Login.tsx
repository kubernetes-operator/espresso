import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useSession } from '../session'

type Demo = { login_id: string; name: string; roles: string }

export function Login() {
  const s = useSession()
  const nav = useNavigate()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [demo, setDemo] = useState<Demo[]>([])

  useEffect(() => { api<Demo[]>('/api/auth/demo-accounts').then(setDemo).catch(() => setDemo([])) }, [])

  const go = async (id = loginId, pw = password) => {
    setBusy(true); setErr(null)
    try { await s.login(id, pw); nav('/') } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="brand big"><span>Cafe de Lyon</span></div>
        <p className="muted">커피숍 물류·회계 통합 시스템</p>
        <form onSubmit={e => { e.preventDefault(); go() }} className="form">
          <label htmlFor="login-id">아이디</label>
          <input id="login-id" value={loginId} onChange={e => setLoginId(e.target.value)} autoComplete="username" />
          <label htmlFor="login-pw">비밀번호</label>
          <input id="login-pw" type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" />
          {err && <div className="error-box">{err}</div>}
          <button className="primary" disabled={busy || !loginId || !password}>{busy ? '로그인 중…' : '로그인'}</button>
        </form>
      </div>
      {demo.length > 0 && (
        <div className="login-card demo">
          <h2>테스트 계정으로 바로 로그인</h2>
          <p className="muted small">데모 전용 계정입니다. 비밀번호는 모두 <code>demo1234</code></p>
          <div className="demo-grid">
            {demo.map(d => (
              <button key={d.login_id} className="demo-btn" onClick={() => go(d.login_id, 'demo1234')} disabled={busy}>
                <b>{d.login_id}</b><span>{d.name}</span><small>{d.roles}</small>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
