import { useState } from 'react'
import './SessionsPreview.css'

const SESSION_COLORS = {
  IT: '#EF4444', SP: '#EF4444', AF: '#F97316', PT: '#84CC16',
  TR: '#9CA3AF', AD: '#EC4899', VG: '#06B6D4', NT: '#06B6D4',
  AG: '#4F6EF7', AI: '#4F6EF7', CF: '#4F6EF7', TRB: '#4F6EF7',
}

const DAY_NAMES = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado']

function groupByDay(sessions) {
  const map = {}
  sessions.forEach(s => {
    const key = s.sessionDate
    if (!map[key]) map[key] = []
    map[key].push(s)
  })
  return Object.entries(map).sort(([a], [b]) => a.localeCompare(b))
}

export default function SessionsPreview({ sessions: initialSessions, weekStart, onExport, onBack }) {
  const [sessions, setSessions] = useState(initialSessions)
  const [exporting, setExporting] = useState(false)

  const toggle = (idx) => {
    setSessions(prev => {
      const next = [...prev]
      next[idx] = { ...next[idx], selected: !next[idx].selected }
      return next
    })
  }

  const updateField = (idx, field, value) => {
    setSessions(prev => {
      const next = [...prev]
      next[idx] = { ...next[idx], [field]: value }
      return next
    })
  }

  const selectedSessions = sessions.filter(s => s.selected && !s.overlapping)
  const hasBlockingOverlap = sessions.some(s => s.selected && s.overlapping)

  const handleExport = async () => {
    setExporting(true)
    await onExport(selectedSessions)
    setExporting(false)
  }

  const grouped = groupByDay(sessions)

  return (
    <div className="preview-container">
      <div className="preview-toolbar">
        <p className="preview-count">
          {selectedSessions.length} sessões selecionadas
          {hasBlockingOverlap && (
            <span className="overlap-warning"> · ⚠️ Resolves as sobreposições para exportar</span>
          )}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-secondary" onClick={onBack}>← Voltar</button>
          <button
            className="btn btn-primary"
            disabled={selectedSessions.length === 0 || hasBlockingOverlap || exporting}
            onClick={handleExport}
          >
            {exporting ? 'A exportar...' : `Exportar ${selectedSessions.length} para o Google Calendar`}
          </button>
        </div>
      </div>

      {grouped.map(([date, daySessions]) => {
        const d = new Date(date + 'T00:00:00')
        const dayName = DAY_NAMES[d.getDay()]
        const formatted = d.toLocaleDateString('pt-PT', { day: 'numeric', month: 'long' })

        return (
          <div key={date} className="preview-day">
            <h3 className="preview-day-title">{dayName}, {formatted}</h3>
            {daySessions.map((s, i) => {
              const globalIdx = sessions.indexOf(s)
              const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
              const isOverlap = s.overlapping

              return (
                <div
                  key={i}
                  className={`preview-session ${isOverlap ? 'overlap' : ''} ${!s.selected ? 'deselected' : ''}`}
                >
                  <input
                    type="checkbox"
                    checked={s.selected}
                    onChange={() => toggle(globalIdx)}
                    className="session-checkbox"
                    disabled={isOverlap}
                  />
                  <div className="session-info">
                    {isOverlap ? (
                      <div className="overlap-cell">
                        ⚠️ Sobreposição — {s.displayName}
                      </div>
                    ) : (
                      <>
                        <span
                          className="type-badge"
                          style={{ background: color + '22', color, border: `1px solid ${color}` }}
                        >
                          {s.sessionTypeAbbrev}
                        </span>
                        <span className="session-display">{s.displayName}</span>
                        {s.location && (
                          <span className="session-location-badge">{s.location}</span>
                        )}
                        <span className="session-time-preview">
                          {s.startTime.slice(0,5)} – {s.endTime.slice(0,5)}
                        </span>
                      </>
                    )}
                  </div>
                  {!isOverlap && (
                    <div className="session-editable">
                      <input
                        type="text"
                        placeholder="Nome da aula"
                        value={s.className || ''}
                        onChange={e => updateField(globalIdx, 'className', e.target.value)}
                        className="inline-edit"
                      />
                      <input
                        type="text"
                        placeholder="Notas"
                        value={s.notes || ''}
                        onChange={e => updateField(globalIdx, 'notes', e.target.value)}
                        className="inline-edit"
                      />
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )
      })}
    </div>
  )
}
