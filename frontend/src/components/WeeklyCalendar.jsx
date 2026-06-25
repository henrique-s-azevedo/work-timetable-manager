/**
 * WeeklyCalendar component — renders a 7-day, time-gridded calendar for a single week.
 *
 * Props:
 * - sessions {Array}   — list of session objects to display; each must have sessionDate,
 *                        startTime, endTime, sessionTypeAbbrev, className, location,
 *                        overlapping (bool), and selected (bool).
 * - weekStart {Date}   — the Monday of the week to display.
 * - onSessionClick {Function} — called with the session object when a block is clicked;
 *                               used by Dashboard to open the edit modal and by
 *                               SessionsPreview to open the detail panel.
 *
 * Layout model:
 * - The calendar covers 06:00–22:15 in 15-minute slots (65 total slots).
 * - Each slot maps to SLOT_HEIGHT (24px) of vertical space, giving a total grid height
 *   of 1560px. This fixed pixel-per-minute ratio makes absolute positioning trivial.
 * - Overlapping sessions within the same day are laid out side-by-side using a greedy
 *   column-packing algorithm (layoutDaySessions). Sessions in the same transitive
 *   overlap cluster share the same total column count so widths are uniform.
 *
 * Visual states:
 * - Normal: colored background tint + left border in the session type color.
 * - Overlapping: amber left border and amber text.
 * - Deselected (selected === false): muted background and text; used in preview mode.
 */
import './WeeklyCalendar.css'

/** Earliest visible time in minutes from midnight (06:00). */
const TIME_START  = 6 * 60

/** Latest visible time in minutes from midnight (22:15). */
const TIME_END    = 22 * 60 + 15

/** Duration of each grid slot in minutes. */
const SLOT_MIN    = 15

/** Pixel height of each 15-minute slot. */
const SLOT_HEIGHT = 24

/** Total number of 15-minute slots in the visible time range. */
const TOTAL_SLOTS = (TIME_END - TIME_START) / SLOT_MIN

const SESSION_COLORS = {
  IT: '#EF4444', SP: '#EF4444',
  AF: '#F97316',
  PT: '#84CC16',
  TR: '#9CA3AF',
  AD: '#EC4899',
  VG: '#06B6D4', NT: '#06B6D4',
  AG: '#4F6EF7', AI: '#4F6EF7', CF: '#4F6EF7', TRB: '#4F6EF7',
}

const DAYS_SHORT = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom']

function timeToMinutes(t) {
  const [h, m] = t.split(':').map(Number)
  return h * 60 + m
}

function slotIndex(timeStr) {
  return (timeToMinutes(timeStr) - TIME_START) / SLOT_MIN
}

function addDays(d, n) {
  const r = new Date(d)
  r.setDate(r.getDate() + n)
  return r
}

function toISODate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * Computes the horizontal layout for sessions within a single day.
 *
 * Each session receives two extra properties:
 * - _col {number}       — zero-based column index within the day column.
 * - _totalCols {number} — total number of columns in the transitive overlap cluster;
 *                         used to calculate percentage width and left offset.
 *
 * Algorithm:
 * 1. Sort sessions by start time.
 * 2. Greedy column assignment: place each session in the first available column
 *    (a column is available if its last session ended before the current one starts).
 * 3. BFS cluster detection: group sessions that are transitively overlapping and
 *    assign the cluster's maximum _col + 1 as _totalCols for all members.
 *
 * @param {Array} sessions - sessions for a single day
 * @returns {Array} a new array with _col and _totalCols added to each session
 */
function layoutDaySessions(sessions) {
  if (!sessions.length) return []

  const sorted = [...sessions]
    .sort((a, b) => timeToMinutes(a.startTime) - timeToMinutes(b.startTime))
    .map(s => ({ ...s }))

  // Greedy column packing: place each session in the first column where it fits
  const colEndTimes = []
  for (const s of sorted) {
    const start = timeToMinutes(s.startTime)
    let col = colEndTimes.findIndex(t => t <= start)
    if (col === -1) { col = colEndTimes.length; colEndTimes.push(timeToMinutes(s.endTime)) }
    else colEndTimes[col] = timeToMinutes(s.endTime)
    s._col = col
  }

  // BFS to find transitive overlap clusters; all sessions in a cluster share _totalCols
  const visited = new Set()
  for (let i = 0; i < sorted.length; i++) {
    if (visited.has(i)) continue
    const cluster = new Set([i])
    const queue = [i]
    while (queue.length) {
      const cur = queue.shift()
      for (let j = 0; j < sorted.length; j++) {
        if (!cluster.has(j) &&
            timeToMinutes(sorted[cur].startTime) < timeToMinutes(sorted[j].endTime) &&
            timeToMinutes(sorted[j].startTime) < timeToMinutes(sorted[cur].endTime)) {
          cluster.add(j)
          queue.push(j)
        }
      }
    }
    const totalCols = Math.max(...[...cluster].map(ci => sorted[ci]._col)) + 1
    cluster.forEach(ci => { sorted[ci]._totalCols = totalCols; visited.add(ci) })
  }

  return sorted
}

export default function WeeklyCalendar({ sessions, weekStart, onSessionClick }) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i))

  const byDay = {}
  days.forEach(d => { byDay[toISODate(d)] = [] })
  sessions.forEach(s => {
    if (byDay[s.sessionDate] !== undefined) byDay[s.sessionDate].push(s)
  })

  const timeLabels = []
  for (let slot = 0; slot <= TOTAL_SLOTS; slot++) {
    const totalMin = TIME_START + slot * SLOT_MIN
    const h = Math.floor(totalMin / 60)
    const m = totalMin % 60
    timeLabels.push({
      slot,
      label: m === 0 ? `${String(h).padStart(2,'0')}:00` : '',
      isHour: m === 0,
    })
  }

  return (
    <div className="weekly-calendar">
      <div className="cal-header">
        <div className="time-gutter" />
        {days.map((d, i) => (
          <div key={i} className="day-header">
            <span className="day-short">{DAYS_SHORT[i]}</span>
            <span className="day-date">{d.getDate()}/{d.getMonth() + 1}</span>
          </div>
        ))}
      </div>

      <div className="cal-grid-wrap">
        <div className="time-col">
          {timeLabels.map(({ slot, label, isHour }) => (
            <div key={slot} className={`time-slot ${isHour ? 'hour' : ''}`}>
              {label && <span className="time-label">{label}</span>}
            </div>
          ))}
        </div>

        {days.map((d, di) => {
          const key = toISODate(d)
          const laid = layoutDaySessions(byDay[key] || [])

          return (
            <div key={di} className="day-col">
              {timeLabels.map(({ slot, isHour }) => (
                <div key={slot} className={`grid-line ${isHour ? 'hour' : ''}`} />
              ))}

              {laid.map((s, si) => {
                const startSlot  = slotIndex(s.startTime)
                const endSlot    = slotIndex(s.endTime)
                const height     = (endSlot - startSlot) * SLOT_HEIGHT
                const top        = startSlot * SLOT_HEIGHT
                const totalCols  = s._totalCols || 1
                const col        = s._col || 0
                const GAP        = 2
                const color      = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
                const isOverlap  = s.overlapping
                const isDeselected = s.selected === false
                const borderColor = isDeselected ? 'var(--border)'
                                  : isOverlap    ? 'var(--warning)'
                                  :                color

                return (
                  <div
                    key={si}
                    className={`session-block${isOverlap ? ' overlap' : ''}${isDeselected ? ' deselected' : ''}`}
                    style={{
                      top:        `${top}px`,
                      height:     `${height}px`,
                      left:       `calc(${(col / totalCols) * 100}% + ${GAP}px)`,
                      width:      `calc(${(1 / totalCols) * 100}% - ${GAP * 2}px)`,
                      background: isDeselected ? 'var(--bg)' : `${color}22`,
                      borderLeft: `3px solid ${borderColor}`,
                      color:      isDeselected ? 'var(--text-muted)' : isOverlap ? '#92400E' : color,
                    }}
                    onClick={() => onSessionClick && onSessionClick(s)}
                  >
                    <span className="session-type">{s.sessionTypeAbbrev}</span>
                    {s.className && <span className="session-class">{s.className}</span>}
                    {s.location  && <span className="session-location">{s.location}</span>}
                    <span className="session-time">{s.startTime.slice(0,5)}–{s.endTime.slice(0,5)}</span>
                  </div>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}
