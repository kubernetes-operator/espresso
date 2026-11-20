import { useEffect, useRef, useState } from 'react'

// 꺾은선 차트 (SVG): 한 개의 y축, 0 기준선, 2px 선·8px 점, 끝점 직접 라벨 + 범례, 마우스 위치 십자선·툴팁, 표 보기
// 색: --series-1(파랑) / --series-2(주황) — 검증된 2색 (CVD ΔE ≥ 24). 이전 연도 등 보조 계열은 점선(색 외 2차 부호화)
export type Series = { key: string; name: string; color: string; dash?: boolean; values: (number | null | undefined)[] }

function niceTicks(min: number, max: number, count = 5) {
  if (min === max) { max = min + 1 }
  const span = max - min
  const step0 = Math.pow(10, Math.floor(Math.log10(span / count)))
  const err = span / count / step0
  const step = step0 * (err >= 7.5 ? 10 : err >= 3.5 ? 5 : err >= 1.5 ? 2 : 1)
  const lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step
  const out: number[] = []
  for (let v = lo; v <= hi + step / 2; v += step) out.push(Math.round(v * 1e6) / 1e6)
  return out
}

export function LineChart({ labels, series, fmt, axisFmt, height = 240, title }: {
  labels: string[]; series: Series[]; fmt: (v: number) => string; axisFmt?: (v: number) => string; height?: number; title: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState<number | null>(null)
  const [table, setTable] = useState(false)
  const [W, setW] = useState(720)
  // 실제 폭에 맞춰 viewBox 를 잡아 글자 크기가 줄어들지 않게
  useEffect(() => {
    const el = ref.current; if (!el) return
    const ro = new ResizeObserver(e => setW(Math.max(320, Math.round(e[0].contentRect.width))))
    ro.observe(el); return () => ro.disconnect()
  }, [])
  const H = height, L = 64, R = 80, T = 12, B = 28
  const vals = series.flatMap(s => s.values.filter((v): v is number => v != null))
  const ticks = niceTicks(Math.min(0, ...vals), Math.max(0, ...vals, 1))
  const y0 = ticks[0], y1 = ticks[ticks.length - 1]
  const x = (i: number) => L + (labels.length <= 1 ? 0 : (i * (W - L - R)) / (labels.length - 1))
  const y = (v: number) => T + (H - T - B) * (1 - (v - y0) / (y1 - y0))
  const path = (vs: Series['values']) => {
    let d = '', pen = false
    vs.forEach((v, i) => {
      if (v == null) { pen = false; return }
      d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`; pen = true
    })
    return d
  }
  const onMove = (e: React.MouseEvent<SVGRectElement>) => {
    const box = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect()
    const px = ((e.clientX - box.left) / box.width) * W
    const i = Math.round(((px - L) / (W - L - R)) * (labels.length - 1))
    setHover(Math.max(0, Math.min(labels.length - 1, i)))
  }
  const lastIdx = (vs: Series['values']) => { for (let i = vs.length - 1; i >= 0; i--) if (vs[i] != null) return i; return -1 }
  const tipLeft = hover == null ? 0 : (x(hover) / W) * 100
  if (vals.length === 0) return <div className="empty">표시할 데이터가 없습니다.</div>
  return (
    <div className="chart" ref={ref}>
      {series.length > 1 && <div className="chart-legend" aria-hidden>
        {series.map(s => <span key={s.key}><i className={s.dash ? 'dash' : ''} style={{ borderColor: s.color }} />{s.name}</span>)}
      </div>}
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
        <g className="grid">{ticks.map(t => <line key={t} x1={L} x2={W - R} y1={y(t)} y2={y(t)} />)}</g>
        {y0 < 0 && <line className="zero" x1={L} x2={W - R} y1={y(0)} y2={y(0)} />}
        <g className="axis">
          {ticks.map(t => <text key={t} x={L - 8} y={y(t) + 4} textAnchor="end">{(axisFmt ?? fmt)(t)}</text>)}
          {labels.map((l, i) => (labels.length <= Math.floor((W - L - R) / 46) || i % 2 === 0) && <text key={l + i} x={x(i)} y={H - 8} textAnchor="middle">{l}</text>)}
        </g>
        {hover != null && <line className="cross" x1={x(hover)} x2={x(hover)} y1={T} y2={H - B} />}
        {series.map(s => {
          const li = lastIdx(s.values)
          return (
            <g key={s.key} className="series">
              <path d={path(s.values)} style={{ stroke: s.color }} strokeDasharray={s.dash ? '6 4' : undefined} />
              {s.values.map((v, i) => v != null && (hover === i || labels.length <= 1) &&
                <circle key={i} cx={x(i)} cy={y(v)} r={4} style={{ fill: s.color }} />)}
              {li >= 0 && series.length <= 4 && <text x={x(li) + 8} y={y(s.values[li] as number) + 4} className="axis" style={{ fill: 'var(--muted)', fontSize: 11 }}>{s.name}</text>}
            </g>
          )
        })}
        <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="transparent" onMouseMove={onMove} />
      </svg>
      {hover != null && (
        <div className="chart-tip" style={{ left: `${tipLeft}%`, top: 8, transform: tipLeft > 60 ? 'translateX(-105%)' : 'translateX(8px)' }}>
          <b>{labels[hover]}</b>
          {series.map(s => <div key={s.key} className="row-v"><span><i style={{ borderColor: s.color, borderTopStyle: s.dash ? 'dashed' : 'solid' }} />{s.name}</span>
            <span className="tnum">{s.values[hover] == null ? '—' : fmt(s.values[hover] as number)}</span></div>)}
        </div>
      )}
      <button className="ghost small" onClick={() => setTable(t => !t)} aria-expanded={table}>{table ? '표 닫기' : '표로 보기'}</button>
      {table && <div className="table-wrap"><table className="tbl compact">
        <thead><tr><th>기간</th>{series.map(s => <th key={s.key} className="num">{s.name}</th>)}</tr></thead>
        <tbody>{labels.map((l, i) => <tr key={l + i}><td>{l}</td>{series.map(s => <td key={s.key} className="num">{s.values[i] == null ? '—' : fmt(s.values[i] as number)}</td>)}</tr>)}</tbody>
      </table></div>}
    </div>
  )
}

/** 원 → 만원 축 표기 */
export const manwon = (v: number) => `${Math.round(v / 10000).toLocaleString('ko-KR')}만`
