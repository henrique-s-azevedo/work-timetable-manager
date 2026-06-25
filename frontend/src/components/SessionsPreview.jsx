/**
 * SessionsPreview component — step 2 of the upload flow.
 *
 * Responsibilities:
 * - Displays all parsed sessions on a WeeklyCalendar so the instructor can visually
 *   review the timetable before exporting.
 * - Detects and groups overlapping sessions in an "Overlap Queue" sidebar panel.
 *   For each overlap group the instructor can choose which session to keep or keep both.
 * - Allows the instructor to click any session to open an inline edit panel where they
 *   can edit className and notes, or deselect the session from the export.
 * - For PT (Personal Training) sessions, renders a PtSplitPanel instead of a plain edit
 *   form, allowing the session to be subdivided into named client slots.
 * - On export, calls expandForExport() to flatten PT groups into individual sessions,
 *   then calls the onExport prop.
 *
 * Props:
 * - sessions {Array}        — initial list of ParsedSessionDTO objects from the backend.
 * - weekStart {Date}        — Monday of the week being previewed.
 * - onExport {Function}     — called with the final expanded session list to trigger export.
 * - onBack {Function}       — called when the user clicks "back" to return to step 1.
 *
 * State management:
 * - sessions: local copy of the initial sessions, augmented with a synthetic _id for
 *   stable identity tracking (parsed index). Mutations (toggles, field edits) happen here.
 * - ptGroupsMap: { [_id]: Group[] } — maps PT session IDs to their slot group definitions.
 * - activeSession: the session currently selected in the sidebar edit panel.
 * - exporting: loading flag while the export API call is in-flight.
 *
 * Key derived values:
 * - overlapGroups: memoized result of findOverlapGroups(); recomputed when sessions change.
 * - unresolvedCount: overlap groups where more than one session is still selected.
 * - selectedSessions: sessions with selected !== false.
 */
import { useState, useMemo } from 'react'
import WeeklyCalendar from './WeeklyCalendar'
import './SessionsPreview.css'

const SESSION_COLORS = {
  IT: '#EF4444', SP: '#EF4444', AF: '#F97316', PT: '#84CC16',
  TR: '#9CA3AF', AD: '#EC4899', VG: '#06B6D4', NT: '#06B6D4',
  AG: '#4F6EF7', AI: '#4F6EF7', CF: '#4F6EF7', TRB: '#4F6EF7',
}

// Only these types carry a "nome da aula" field
const HAS_CLASS_NAME = new Set(['AG', 'AI', 'CF', 'TRB', 'NT'])

const DAY_NAMES = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado']

function timeToMinutes(t) {
  const [h, m] = t.split(':').map(Number)
  return h * 60 + m
}

function minutesToTime(min) {
  return `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`
}

function addDays(d, n) {
  const r = new Date(d); r.setDate(r.getDate() + n); return r
}

/**
 * Groups sessions into clusters of transitively overlapping sessions per day.
 *
 * Uses a BFS approach: for each unvisited session, builds a cluster by adding any
 * session that overlaps it (directly or transitively). Only clusters with more than
 * one member are included in the result — single sessions are not reported as overlaps.
 *
 * The overlap check uses exclusive end times (half-open intervals): sessions that share
 * only an endpoint (e.g., 09:00–10:00 and 10:00–11:00) are not considered overlapping.
 *
 * @param {Array} sessions - all sessions (across all days) to check
 * @returns {Array<Array>} array of overlap groups, each group being an array of sessions;
 *                         sorted by the first session's date
 */
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
            cluster.add(j); queue.push(j)
          }
        }
      }
      if (cluster.size > 1) groups.push([...cluster].map(idx => daySessions[idx]))
      cluster.forEach(i => visited.add(i))
    }
  }
  return groups.sort((a, b) => a[0].sessionDate.localeCompare(b[0].sessionDate))
}

/**
 * Expands PT sessions that have been split into named client groups into individual
 * export-ready sessions.
 *
 * For PT sessions with groups defined in ptGroupsMap:
 * - Each named group becomes a separate session with adjusted startTime/endTime and
 *   className set to the group's student name.
 * - Unassigned slots (not covered by any group) are collected into contiguous ranges
 *   and exported as additional unnamed sessions (className: null).
 *
 * Non-PT sessions and PT sessions without any groups are passed through unchanged.
 *
 * @param {Array} sessions     - the selected sessions to export
 * @param {Object} ptGroupsMap - { [_id]: { id, slots, name }[] } from PtSplitPanel state
 * @returns {Array} expanded list ready for POST /api/timetable/export
 */
function expandForExport(sessions, ptGroupsMap) {
  const result = []
  for (const s of sessions) {
    const groups = ptGroupsMap[s._id]
    if (s.sessionTypeAbbrev !== 'PT' || !groups?.length) {
      result.push(s)
      continue
    }
    const startMin   = timeToMinutes(s.startTime)
    const endMin     = timeToMinutes(s.endTime)
    const numSlots   = (endMin - startMin) / 15
    const assigned   = new Set(groups.flatMap(g => g.slots))

    // Named groups (sorted by first slot)
    const sorted = [...groups].sort((a, b) => Math.min(...a.slots) - Math.min(...b.slots))
    for (const g of sorted) {
      const slots = [...g.slots].sort((a, b) => a - b)
      result.push({
        ...s,
        startTime: minutesToTime(startMin + slots[0] * 15),
        endTime:   minutesToTime(startMin + (slots[slots.length - 1] + 1) * 15),
        className: g.name || null,
      })
    }

    // Unassigned slots → contiguous ranges exported without a name
    let rangeStart = null
    for (let i = 0; i <= numSlots; i++) {
      const free = i < numSlots && !assigned.has(i)
      if (free && rangeStart === null) { rangeStart = i }
      if (!free && rangeStart !== null) {
        result.push({
          ...s,
          startTime: minutesToTime(startMin + rangeStart * 15),
          endTime:   minutesToTime(startMin + i * 15),
          className: null,
        })
        rangeStart = null
      }
    }
  }
  return result
}

// ── PT split panel ────────────────────────────────────────────────────────────

/**
 * PtSplitPanel — inline editor for subdividing a Personal Training session into
 * named client slots.
 *
 * Renders the PT session as a grid of 15-minute slot buttons. The instructor selects
 * contiguous or non-contiguous slots, optionally types a student name, and clicks
 * "Criar grupo" to assign those slots to the named client.
 *
 * Props:
 * - session {Object}         — the PT session being split (provides start/end time).
 * - groups {Array}           — current list of { id, slots, name } group definitions.
 * - onChange {Function}      — called with the updated groups array on every change.
 *
 * State:
 * - selectedSlots {Set}      — indices of currently highlighted (uncommitted) slots.
 * - studentName {string}     — the name being typed for the next group.
 *
 * UX decisions:
 * - Already-assigned slots are rendered as disabled buttons showing the student name.
 * - The "Criar grupo" button is only shown when at least one slot is selected.
 * - Unassigned slots remaining after all groups are defined are shown as a summary
 *   row so the instructor knows how many free slots will be exported without a name.
 */
function PtSplitPanel({ session, groups, onChange }) {
  const [selectedSlots, setSelectedSlots] = useState(new Set())
  const [studentName, setStudentName]     = useState('')

  const startMin  = timeToMinutes(session.startTime)
  const numSlots  = (timeToMinutes(session.endTime) - startMin) / 15
  const assigned  = new Set(groups.flatMap(g => g.slots))

  const slotTime = (idx) => minutesToTime(startMin + idx * 15)

  const toggleSlot = (idx) => {
    if (assigned.has(idx)) return
    setSelectedSlots(prev => {
      const next = new Set(prev)
      next.has(idx) ? next.delete(idx) : next.add(idx)
      return next
    })
  }

  const addGroup = () => {
    if (!selectedSlots.size) return
    onChange([...groups, {
      id:    Date.now(),
      slots: [...selectedSlots].sort((a, b) => a - b),
      name:  studentName.trim(),
    }])
    setSelectedSlots(new Set())
    setStudentName('')
  }

  const removeGroup = (id) => onChange(groups.filter(g => g.id !== id))

  const unassignedCount = numSlots - assigned.size

  return (
    <div className="pt-split-panel">
      <p className="pt-split-hint">
        Clica nos slots para selecionar, depois atribui um nome de aluno.
      </p>

      <div className="pt-slots">
        {Array.from({ length: numSlots }, (_, i) => {
          const isAssigned = assigned.has(i)
          const isSelected = selectedSlots.has(i)
          const ownerGroup = groups.find(g => g.slots.includes(i))
          return (
            <button
              key={i}
              className={`pt-slot${isAssigned ? ' assigned' : ''}${isSelected ? ' selected' : ''}`}
              onClick={() => toggleSlot(i)}
              disabled={isAssigned}
              title={isAssigned ? (ownerGroup?.name || 'sem nome') : `${slotTime(i)}–${slotTime(i + 1)}`}
            >
              <span className="pt-slot-time">{slotTime(i)}</span>
              {isAssigned && ownerGroup?.name && (
                <span className="pt-slot-label">{ownerGroup.name}</span>
              )}
            </button>
          )
        })}
      </div>

      {selectedSlots.size > 0 && (
        <div className="pt-add-group">
          <input
            type="text"
            placeholder="Nome do aluno"
            value={studentName}
            onChange={e => setStudentName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && addGroup()}
            className="inline-edit"
            autoFocus
          />
          <button className="btn btn-sm btn-primary" onClick={addGroup}>
            Criar grupo ({selectedSlots.size}×15 min)
          </button>
        </div>
      )}

      {groups.length > 0 && (
        <div className="pt-groups-list">
          {[...groups]
            .sort((a, b) => Math.min(...a.slots) - Math.min(...b.slots))
            .map(g => {
              const slots = [...g.slots].sort((a, b) => a - b)
              return (
                <div key={g.id} className="pt-group-item">
                  <span className="pt-group-time">
                    {slotTime(slots[0])}–{slotTime(slots[slots.length - 1] + 1)}
                  </span>
                  <span className="pt-group-name">{g.name || <em>sem nome</em>}</span>
                  <button className="btn-close" onClick={() => removeGroup(g.id)}>✕</button>
                </div>
              )
            })
          }
          {unassignedCount > 0 && (
            <div className="pt-group-item unassigned">
              <span className="pt-group-time">restantes ({unassignedCount}×15 min)</span>
              <span className="pt-group-name"><em>sem nome</em></span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Main component ────────────────────────────────────────────────────────────

export default function SessionsPreview({ sessions: initialSessions, weekStart, onExport, onBack }) {
  const [sessions, setSessions] = useState(
    () => initialSessions.map((s, i) => ({ ...s, _id: i, selected: s.selected !== false }))
  )
  const [ptGroupsMap, setPtGroupsMap]       = useState({})   // { [_id]: Group[] }
  const [exporting, setExporting]           = useState(false)
  const [activeSession, setActiveSession]   = useState(null)

  const weekEnd = addDays(weekStart, 6)

  const toggleById = (id) => {
    setSessions(prev => prev.map(s => s._id === id ? { ...s, selected: !s.selected } : s))
    setActiveSession(prev => prev?._id === id ? { ...prev, selected: !prev.selected } : prev)
  }

  const updateField = (id, field, value) => {
    setSessions(prev => prev.map(s => s._id === id ? { ...s, [field]: value } : s))
    setActiveSession(prev => prev?._id === id ? { ...prev, [field]: value } : prev)
  }

  const keepOnly = (groupIds, keepIds) => {
    setSessions(prev => prev.map(s =>
      groupIds.includes(s._id) ? { ...s, selected: keepIds.includes(s._id) } : s
    ))
  }

  const setPtGroups = (id, groups) =>
    setPtGroupsMap(prev => ({ ...prev, [id]: groups }))

  const overlapGroups = useMemo(() => findOverlapGroups(sessions), [sessions])

  const unresolvedCount = overlapGroups.filter(group =>
    group.filter(gs => sessions.find(x => x._id === gs._id)?.selected !== false).length > 1
  ).length

  const selectedSessions = sessions.filter(s => s.selected !== false)

  const handleExport = async () => {
    setExporting(true)
    await onExport(expandForExport(selectedSessions, ptGroupsMap))
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
                const live    = group.map(gs => sessions.find(x => x._id === gs._id) || gs)
                const groupIds = live.map(s => s._id)
                const d        = new Date(live[0].sessionDate + 'T00:00:00')
                const minStart = live.reduce((m, s) => s.startTime < m ? s.startTime : m, live[0].startTime)
                const maxEnd   = live.reduce((m, s) => s.endTime   > m ? s.endTime   : m, live[0].endTime)
                return (
                  <div key={gi} className="overlap-card">
                    <div className="overlap-card-date">
                      {DAY_NAMES[d.getDay()]} · {minStart.slice(0,5)}–{maxEnd.slice(0,5)}
                    </div>
                    {live.map(s => {
                      const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
                      return (
                        <div key={s._id} className={`overlap-session-row${s.selected === false ? ' deselected' : ''}`}>
                          <span className="type-badge" style={{ background: color + '22', color, border: `1px solid ${color}` }}>
                            {s.sessionTypeAbbrev}
                          </span>
                          <span className="overlap-session-name">{s.displayName}</span>
                          {s.className && <span className="overlap-class-name">· {s.className}</span>}
                        </div>
                      )
                    })}
                    <div className="overlap-actions">
                      {live.map(s => (
                        <button key={s._id} className="btn btn-sm btn-secondary"
                          onClick={() => keepOnly(groupIds, [s._id])}>
                          Só {s.sessionTypeAbbrev}
                        </button>
                      ))}
                      <button className="btn btn-sm btn-secondary"
                        onClick={() => keepOnly(groupIds, groupIds)}>
                        Ambas
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}

          {/* Edit / split panel */}
          {activeSession && (() => {
            const s     = sessions.find(x => x._id === activeSession._id) || activeSession
            const color = SESSION_COLORS[s.sessionTypeAbbrev] || '#4F6EF7'
            const isPT  = s.sessionTypeAbbrev === 'PT'
            return (
              <div className="session-edit-panel">
                <div className="edit-panel-header">
                  <span>{isPT ? 'Dividir PT' : 'Editar sessão'}</span>
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

                {isPT ? (
                  <PtSplitPanel
                    key={s._id}
                    session={s}
                    groups={ptGroupsMap[s._id] || []}
                    onChange={groups => setPtGroups(s._id, groups)}
                  />
                ) : (
                  <div className="edit-panel-fields">
                    {HAS_CLASS_NAME.has(s.sessionTypeAbbrev) && (
                      <>
                        <label>Nome da aula</label>
                        <input
                          type="text"
                          value={s.className || ''}
                          onChange={e => updateField(s._id, 'className', e.target.value)}
                          placeholder="Nome da aula"
                          className="inline-edit"
                        />
                      </>
                    )}
                    <label>Notas</label>
                    <input
                      type="text"
                      value={s.notes || ''}
                      onChange={e => updateField(s._id, 'notes', e.target.value)}
                      placeholder="Notas"
                      className="inline-edit"
                    />
                  </div>
                )}

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

          {overlapGroups.length === 0 && !activeSession && (
            <p className="preview-hint">Clica numa sessão para editar ou excluir da exportação.</p>
          )}
        </div>
      </div>
    </div>
  )
}
