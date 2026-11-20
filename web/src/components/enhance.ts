// 화면 공통 보강 (2차 점검 반영)
//  1) 모든 표(.tbl, .sched) 머리글을 눌러 정렬 — 숫자·날짜·한글 구분, 다시 누르면 반대 방향
//     데이터가 다시 그려져도(새로고침·필터) 고른 정렬을 다시 적용. 합계 행(.b)과 빈 행은 맨 아래 유지
//  2) 필수 입력(required) 이 비어 있으면 제출을 막고 어떤 항목인지 표시

const collator = new Intl.Collator('ko', { numeric: true, sensitivity: 'base' })

function cellValue(td: Element | undefined): { n: number | null; s: string } {
  if (!td) return { n: null, s: '' }
  const input = td.querySelector('input, select') as HTMLInputElement | HTMLSelectElement | null
  const raw = (input ? (input instanceof HTMLSelectElement ? input.selectedOptions[0]?.text ?? '' : input.value) : td.textContent ?? '').trim()
  const num = raw.replace(/[,원%h]|\s/g, '')
  if (/^[-−]?\d+(\.\d+)?$/.test(num)) return { n: Number(num.replace('−', '-')), s: raw }
  return { n: null, s: raw }
}

type SortState = { col: number; dir: 1 | -1 }
const states = new WeakMap<HTMLTableElement, SortState>()
const sorting = new WeakSet<HTMLTableElement>()

function apply(table: HTMLTableElement) {
  const st = states.get(table)
  const tbody = table.tBodies[0]
  if (!st || !tbody) return
  const rows = [...tbody.rows]
  const fixed = rows.filter(r => r.classList.contains('b') || r.cells.length === 1)
  const body = rows.filter(r => !fixed.includes(r))
  const sorted = [...body].sort((a, b) => {
    const x = cellValue(a.cells[st.col]), y = cellValue(b.cells[st.col])
    if (x.n != null && y.n != null) return (x.n - y.n) * st.dir
    if (!x.s) return 1
    if (!y.s) return -1
    return collator.compare(x.s, y.s) * st.dir
  })
  if (sorted.every((r, i) => r === body[i])) return
  sorting.add(table)
  for (const r of [...sorted, ...fixed]) tbody.appendChild(r)
  queueMicrotask(() => sorting.delete(table))
}

function mark(table: HTMLTableElement) {
  const st = states.get(table)
  const ths = table.tHead?.rows[0]?.cells ?? []
  ;[...ths].forEach((th, i) => {
    th.classList.toggle('sorted-asc', !!st && st.col === i && st.dir === 1)
    th.classList.toggle('sorted-desc', !!st && st.col === i && st.dir === -1)
    th.setAttribute('aria-sort', st && st.col === i ? (st.dir === 1 ? 'ascending' : 'descending') : 'none')
  })
}

const observed = new WeakSet<HTMLTableElement>()
function observe(table: HTMLTableElement) {
  if (observed.has(table)) return
  observed.add(table)
  new MutationObserver(() => { if (!sorting.has(table)) apply(table) }).observe(table, { childList: true, subtree: true, characterData: true })
}

export function installTableSort() {
  document.addEventListener('click', e => {
    const th = (e.target as HTMLElement).closest('th') as HTMLTableCellElement | null
    if (!th || (e.target as HTMLElement).closest('button, input, select, a')) return
    const table = th.closest('table.tbl, table.sched') as HTMLTableElement | null
    if (!table || th.parentElement?.parentElement?.tagName !== 'THEAD' || !th.textContent?.trim()) return
    const col = th.cellIndex
    const cur = states.get(table)
    states.set(table, { col, dir: cur && cur.col === col ? (cur.dir === 1 ? -1 : 1) : 1 })
    observe(table)
    apply(table)
    mark(table)
  })
}

/** 필수 입력 확인: <form> 안의 [required] 가 비어 있으면 제출을 막고 항목 이름을 보여 줌 */
function labelOf(el: HTMLElement): string {
  const id = el.id
  const lab = (id && document.querySelector(`label[for="${CSS.escape(id)}"]`)) || el.closest('div')?.querySelector('label')
  const t = (lab?.textContent || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '항목').replace(/\*|\(.*?\)|—.*$/g, '').trim()
  return t || '항목'
}

export function checkRequired(form: HTMLElement): string[] {
  form.querySelectorAll('.missing').forEach(x => x.classList.remove('missing'))
  const miss: HTMLElement[] = []
  form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('[required]').forEach(el => {
    if (el.disabled || el.offsetParent === null) return
    if (!String(el.value ?? '').trim()) miss.push(el)
  })
  miss.forEach(el => el.classList.add('missing'))
  let box = form.querySelector(':scope > .required-box') as HTMLElement | null
  if (miss.length) {
    if (!box) { box = document.createElement('div'); box.className = 'error-box required-box'; box.setAttribute('role', 'alert'); form.prepend(box) }
    box.textContent = `필수 항목을 입력하세요: ${[...new Set(miss.map(labelOf))].join(', ')}`
    miss[0].focus()
  } else box?.remove()
  return miss.map(labelOf)
}

export function installRequiredCheck() {
  // React 보다 먼저 받는 캡처 단계에서 막음 (React onSubmit 은 호출되지 않음)
  window.addEventListener('submit', e => {
    const form = e.target as HTMLFormElement
    if (form.hasAttribute('novalidate-app')) return
    if (checkRequired(form).length) { e.preventDefault(); e.stopPropagation() }
  }, true)
  // 브라우저 기본 말풍선 대신 같은 안내 상자로 (invalid 는 submit 보다 먼저 발생)
  let pending = false
  document.addEventListener('invalid', e => {
    e.preventDefault()
    const form = (e.target as HTMLInputElement).form
    if (!form || pending) return
    pending = true
    queueMicrotask(() => { pending = false; checkRequired(form) })
  }, true)
  // 입력하면 표시 해제
  document.addEventListener('input', e => {
    const el = e.target as HTMLElement
    if (el.classList?.contains('missing') && (el as HTMLInputElement).value) el.classList.remove('missing')
  })
}
