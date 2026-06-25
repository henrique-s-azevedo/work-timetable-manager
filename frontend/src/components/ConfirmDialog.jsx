/**
 * ConfirmDialog component — modal confirmation dialog for destructive actions.
 *
 * Props:
 * - message {string}     — the confirmation prompt text shown to the user.
 * - onConfirm {Function} — called when the user clicks "Confirmar".
 * - onCancel {Function}  — called when the user clicks "Cancelar".
 *
 * Used by the Dashboard to gate the "Clear Week" action, which would permanently
 * remove sessions from both the database and Google Calendar.
 */
export default function ConfirmDialog({ message, onConfirm, onCancel }) {
  return (
    <div className="modal-overlay">
      <div className="modal" style={{ maxWidth: 440 }}>
        <div className="modal-header">
          <h2>Confirmação</h2>
        </div>
        <p style={{ fontSize: 14, color: 'var(--text)', marginBottom: 24, lineHeight: 1.6 }}>
          {message}
        </p>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button className="btn btn-secondary" onClick={onCancel}>Cancelar</button>
          <button className="btn btn-danger" onClick={onConfirm}>Confirmar</button>
        </div>
      </div>
    </div>
  )
}
