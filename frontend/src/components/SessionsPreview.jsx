import { useState, useMemo } from 'react'
import WeeklyCalendar from './WeeklyCalendar'
import './SessionsPreview.css'

const SESSION_COLORS = {
  IT: '#EF4444', SP: '#EF4444', AF: '#F97316', PT: '#84CC16',
  TR: '#9CA3AF', AD: '#EC4899', VG: '#06B6D4', NT: '#06B6D4',
  AG: '#4F6EF7', AI: '#4F6EF7', CF: '#4F6EF7', TRB: '#4F6EF7',
}

const DAY_NAMES = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado']

function timeToMinutes(t) {
  const [h, m] = t.split(':').map(Number)
  return h * 60 + m
}

function addDays(d, n) {
  const r = new Date(d); r.setDate(r.getDate() + n); return r
}

// Groups sessions into clusters of overlapping sessions (by actual time, per day)
function findOverlapGroups(sessions) {
  const byDate = {}
  sessions.forEach(s => {
    if (!byDate[s.sessionDate]) byDate[s.sessionDate] = []
    byDate[s.sessionDate].push(s)
  })

  const groups = []
  for (const daySessions of Object.values(byDate)) {
    const visited = new Set()
    for (let i = 0; i < daySessions.length; i++) {
      if (visited.has(i)) continue
      const cluster = new Set([i])
      const queue = [i]
      while (queue.length) {
        const cur = queue.shift()
        for (let j = 0; j < daySessions.length; j++) {
          if (!cluster.has(j) &&
              timeToMinutes(daySessions[cur].startTime) < timeToMinutes(daySessions[j].endTime) &&
              timeToMinutes(daySessions[j].startTime)  < timeToMinutes(daySessions[cur].endTime)) {
            cluster.add(j)
            queue.push(j)
          }
        }
      }
      if (cluster.size > 1) groups.push([...cluster].map(idx => daySessions[idx]))
      cluster.forEach(i => visited.add(i))
    }
  }
  return groups.sort((a, b) => a[0].sessionDate.localeCompare(b[0].sessionDate))
}

export default function SessionsPreview({ sessions: initialSessions, weekStart, onExport, onBack }) {
  const [sessions, setSessions] = useState(
    () => initialSessions.map((s, i) => ({ ...s, _id: i, selected: s.selected !== false }))
  )
  const [exporting, setExporting]       = useState(false)
  const [activeSession, setActiveSession] = useState(null)

  const weekEnd = addDays(weekStart, 6)

  const toggleById = (id) => {
    setSessions(prev => prev.map(s => s._id === id ? { ...s, selected: !s.selected } : s))
    setActiveSession(prev => prev?._id === id ? { ...prev, selected: !prev.selected } : prev)
  }

  const updateField = (id, field, value) => {
    setSessions(prev => prev.map(s => s._id === id ? { ...s, [field]: value } : s))
    setActiveSession(prev => prev?._id === id ? { ...prev, [field]: value } : prev)
  }

  // Keep only the sessions whose _id is in keepIds, deselect the rest in the group
  const keepOnly = (groupIds, keepIds) => {
    setSessions(prev => prev.map(s =>
      groupIds.includes(s._id) ? { ...s, selected: keepIds.includes(s._id) } : s
    ))
  }

  const overlapGroups = useMemo(() => findOverlapGroups(sessions), [sessions])

  // A group is "unresolved" when more than one session in it is still selected
  const unresolvedCount = overlapGroups.filter(group =>
    group.filter(gs => sessions.find(x => x._id === gs._id)?.selected !== false).length > 1
  ).length

  const selectedSessions = sessions.filter(s => s.selected !== false)

  const handleExport = async () => {
    setExporting(true)
    await onExport(selectedSessions)
    setExporting(false)
  }

  const handleSessionClick = (s) => {
    const found = sessions.find(x => x._id === s._id)
    if (found) setActiveSession(found)
  }

  return (
    <div className="preview-layout">
      {/* Toolbar */}
      <div className="preview-toolbar">
        <p className="preview-count">
          {selectedSessions.length} sessões selecionadas
          {unresolvedCount > 0 && (
            <span className="overlap-warning">
              {' '}· ⚠️ {unresolvedCount} sobreposição{unresolvedCount > 1 ? 'ões' : ''} por resolver
            </span>
          )}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-secondary" onClick={onBack}>← Voltar</button>
          <button
            className="btn btn-primary"
            disabled={selectedSessions.length === 0 || exporting}
            onClick={handleExport}
          >
            {exporting ? 'A exportar...' : `Exportar ${selectedSessions.length} para o Google Calendar`}
          </button>
        </div>
      </div>

      {/* Calendar + sidebar */}
      <div className="preview-content">
        <div className="preview-calendar">
          <WeeklyCalendar
            sessions={sessions}
            weekStart={weekStart}
            weekEnd={weekEnd}
            onSessionClick={handleSessionClick}
          />
        </div>

        <div className="preview-sidebar">
          {/* Overlap queue */}
          {overlapGroups.length > 0 && (
            <div className="overlap-queue">
              <h3 className="overlap-queue-title">⚠️ Sobreposições ({overlapGroups.length})</h3>
              {overlapGroups.map((group, gi) => {
                // Always read live state
                const live = group.map(gs => sessions.find(x => x._id === gs._id) || gs)
                const groupIds = live.map(s => s._id)
                const d = new Date(live[0].sessionDate + 'T00:00:00')
                const dayLabel = DAY_NAMES[d.getDay()]
                const minStart = live.reduce((m, s) => s.startTime < m ? s.startTime : m, live[0].startTime)
                const maxEnd   = live.reduce((m, s) => s.endTime   > m ? s.endTime   : m, live[0].endTime)

                return (
                  <div key={gi} className="overlap-card">
                    <div className="overlap-card-date">
                      {dayLabel} · {minStart.slice(0,5)}–{maxEnd.slice(0,5)}
                    </div>

                    {live.map(s => {
                      const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
                      const deselected = s.selected === false
                      return (
                        <div key={s._id} className={`overlap-session-row${deselected ? ' deselected' : ''}`}>
                          <span
                            className="type-badge"
                            style={{ background: color + '22', color, border: `1px solid ${color}` }}
                          >
                            {s.sessionTypeAbbrev}
                          </span>
                          <span className="overlap-session-name">{s.displayName}</span>
                          {s.className && <span className="overlap-class-name">· {s.className}</span>}
                        </div>
                      )
                    })}

                    <div className="overlap-actions">
                      {live.map(s => (
                        <button
                          key={s._id}
                          className="btn btn-sm btn-secondary"
                          onClick={() => keepOnly(groupIds, [s._id])}
                        >
                          Só {s.sessionTypeAbbrev}
                        </button>
                      ))}
                      <button
                        className="btn btn-sm btn-secondary"
                        onClick={() => keepOnly(groupIds, groupIds)}
                      >
                        Ambas
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}

          {/* Edit panel for clicked session */}
          {activeSession && (() => {
            const s = sessions.find(x => x._id === activeSession._id) || activeSession
            const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
            return (
              <div className="session-edit-panel">
                <div className="edit-panel-header">
                  <span>Editar sessão</span>
                  <button className="btn-close" onClick={() => setActiveSession(null)}>✕</button>
                </div>

                <div className="edit-panel-info">
                  <span className="type-badge" style={{ background: color + '22', color, border: `1px solid ${color}` }}>
                    {s.sessionTypeAbbrev}
                  </span>
                  <span style={{ fontSize: 13, fontWeight: 500 }}>{s.displayName}</span>
                  <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                    {s.startTime.slice(0,5)}–{s.endTime.slice(0,5)}
                  </span>
                </div>

                <div className="edit-panel-fields">
                  <label>Nome da aula</label>
                  <input
                    type="text"
                    value={s.className || ''}
                    onChange={e => updateField(s._id, 'className', e.target.value)}
                    placeholder="Nome da aula"
                    className="inline-edit"
                  />
                  <label>Notas</label>
                  <input
                    type="text"
                    value={s.notes || ''}
                    onChange={e => updateField(s._id, 'notes', e.target.value)}
                    placeholder="Notas"
                    className="inline-edit"
                  />
                </div>

                <label className="toggle-label">
                  <input
                    type="checkbox"
                    checked={s.selected !== false}
                    onChange={() => toggleById(s._id)}
                    className="session-checkbox"
                  />
                  Incluir na exportação
                </label>
              </div>
            )
          })()}

          {/* Hint when sidebar is empty */}
          {overlapGroups.length === 0 && !activeSession && (
            <p className="preview-hint">Clica numa sessão para editar ou excluir da exportação.</p>
          )}
        </div>
      </div>
    </div>
  )
}
