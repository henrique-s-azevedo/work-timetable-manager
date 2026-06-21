import './WeeklyCalendar.css'

const TIME_START = 6 * 60  // 06:00 in minutes
const TIME_END   = 22 * 60 + 15  // 22:15
const SLOT_MIN   = 15
const TOTAL_SLOTS = (TIME_END - TIME_START) / SLOT_MIN  // 65 slots → 22:15 is the end boundary

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

export default function WeeklyCalendar({ sessions, weekStart, weekEnd, onSessionClick }) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i))

  // Group sessions by day
  const byDay = {}
  days.forEach(d => { byDay[toISODate(d)] = [] })
  sessions.forEach(s => {
    const key = s.sessionDate
    if (byDay[key] !== undefined) byDay[key].push(s)
  })

  // Generate time labels at each hour
  const timeLabels = []
  for (let slot = 0; slot <= TOTAL_SLOTS; slot++) {
    const totalMin = TIME_START + slot * SLOT_MIN
    const h = Math.floor(totalMin / 60)
    const m = totalMin % 60
    const label = m === 0 ? `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}` : ''
    timeLabels.push({ slot, label, isHour: m === 0 })
  }

  return (
    <div className="weekly-calendar">
      {/* Day header row */}
      <div className="cal-header">
        <div className="time-gutter" />
        {days.map((d, i) => (
          <div key={i} className="day-header">
            <span className="day-short">{DAYS_SHORT[i]}</span>
            <span className="day-date">{d.getDate()}/{d.getMonth() + 1}</span>
          </div>
        ))}
      </div>

      {/* Grid */}
      <div className="cal-grid-wrap">
        {/* Time column */}
        <div className="time-col">
          {timeLabels.map(({ slot, label, isHour }) => (
            <div key={slot} className={`time-slot ${isHour ? 'hour' : ''}`}>
              {label && <span className="time-label">{label}</span>}
            </div>
          ))}
        </div>

        {/* Day columns */}
        {days.map((d, di) => {
          const key = toISODate(d)
          const daySessions = byDay[key] || []
          return (
            <div key={di} className="day-col">
              {/* Grid lines */}
              {timeLabels.map(({ slot, isHour }) => (
                <div key={slot} className={`grid-line ${isHour ? 'hour' : ''}`} />
              ))}
              {/* Session blocks */}
              {daySessions.map((s, si) => {
                const startSlot = slotIndex(s.startTime)
                const endSlot = slotIndex(s.endTime)
                const height = (endSlot - startSlot) * 24 // 24px per slot
                const top = startSlot * 24
                const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
                const isOverlap = s.overlapping

                return (
                  <div
                    key={si}
                    className={`session-block ${isOverlap ? 'overlap' : ''}`}
                    style={{
                      top: `${top}px`,
                      height: `${height}px`,
                      background: isOverlap ? 'var(--overlap-light)' : `${color}22`,
                      borderLeft: `3px solid ${isOverlap ? 'var(--overlap)' : color}`,
                      color: isOverlap ? 'var(--overlap)' : color,
                    }}
                    onClick={() => onSessionClick && onSessionClick(s)}
                  >
                    {isOverlap ? (
                      <span className="overlap-badge">⚠️ Sobreposição</span>
                    ) : (
                      <>
                        <span className="session-type">{s.sessionTypeAbbrev}</span>
                        {s.className && <span className="session-class">{s.className}</span>}
                        {s.location && <span className="session-location">{s.location}</span>}
                        <span className="session-time">{s.startTime.slice(0,5)}–{s.endTime.slice(0,5)}</span>
                      </>
                    )}
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
