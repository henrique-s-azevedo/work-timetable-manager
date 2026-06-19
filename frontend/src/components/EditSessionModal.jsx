import { useState } from 'react'
import api from '../api'

const SESSION_COLORS = {
  IT: '#EF4444', SP: '#EF4444', AF: '#F97316', PT: '#84CC16',
  TR: '#9CA3AF', AD: '#EC4899', VG: '#06B6D4', NT: '#06B6D4',
  AG: '#4F6EF7', AI: '#4F6EF7', CF: '#4F6EF7', TRB: '#4F6EF7',
}

function formatDateTime(session) {
  const d = new Date(session.sessionDate + 'T00:00:00')
  const dateStr = d.toLocaleDateString('pt-PT', { weekday: 'long', day: 'numeric', month: 'long' })
  return `${dateStr}, ${session.startTime.slice(0,5)} – ${session.endTime.slice(0,5)}`
}

export default function EditSessionModal({ session, onSave, onDelete, onClose }) {
  const [className, setClassName] = useState(session.className || '')
  const [notes, setNotes] = useState(session.notes || '')
  const [saving, setSaving] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const color = SESSION_COLORS[session.sessionTypeAbbrev] || '#4F6EF7'

  const handleSave = async (e) => {
    e.preventDefault()
    setSaving(true)
    try {
      const res = await api.patch(`/api/timetable/sessions/${session.id}`, { className, notes })
      onSave(res.data)
    } catch (err) {
      console.error(err)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    setDeleting(true)
    try {
      await api.delete(`/api/timetable/sessions/${session.id}`)
      onDelete(session.id)
    } catch (err) {
      console.error(err)
      setDeleting(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={e => e.target === e.currentTarget && onClose()}>
      <div className="modal">
        <div className="modal-header">
          <h2>Editar Sessão</h2>
          <button className="modal-close" onClick={onClose}>×</button>
        </div>

        <div style={{ marginBottom: 16 }}>
          <span style={{
            display: 'inline-block',
            background: color + '22',
            color,
            border: `1px solid ${color}`,
            borderRadius: 6,
            padding: '3px 10px',
            fontSize: 12,
            fontWeight: 700,
          }}>
            {session.displayName}
          </span>
        </div>

        <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
          {formatDateTime(session)}
        </p>

        <form onSubmit={handleSave}>
          <div className="form-group">
            <label>Nome da aula / Título</label>
            <input
              type="text"
              value={className}
              onChange={e => setClassName(e.target.value)}
              placeholder="Ex: RPM, Pilates..."
            />
          </div>

          <div className="form-group">
            <label>Notas</label>
            <textarea
              value={notes}
              onChange={e => setNotes(e.target.value)}
              placeholder="Notas opcionais..."
            />
          </div>

          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancelar
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'A guardar...' : 'Guardar'}
            </button>
          </div>
        </form>

        <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--border)' }}>
          {!confirmDelete ? (
            <button
              style={{ background: 'none', border: 'none', color: 'var(--danger)', fontSize: 13, cursor: 'pointer', textDecoration: 'underline' }}
              onClick={() => setConfirmDelete(true)}
            >
              Eliminar sessão
            </button>
          ) : (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: 13, color: 'var(--danger)' }}>Tens a certeza?</span>
              <button className="btn btn-danger" style={{ padding: '6px 12px', fontSize: 13 }} onClick={handleDelete} disabled={deleting}>
                {deleting ? 'A eliminar...' : 'Confirmar'}
              </button>
              <button className="btn btn-secondary" style={{ padding: '6px 12px', fontSize: 13 }} onClick={() => setConfirmDelete(false)}>
                Cancelar
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
