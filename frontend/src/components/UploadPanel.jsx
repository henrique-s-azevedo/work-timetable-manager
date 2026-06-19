import { useState, useRef } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api'
import SessionsPreview from './SessionsPreview'
import Toast from './Toast'
import './UploadPanel.css'

function toMonday(dateStr) {
  const d = new Date(dateStr + 'T00:00:00')
  const day = d.getDay() || 7
  d.setDate(d.getDate() - (day - 1))
  return d
}

function formatWeekLabel(monday) {
  if (!monday) return ''
  const sunday = new Date(monday)
  sunday.setDate(monday.getDate() + 6)
  const fmt = (d) => d.toLocaleDateString('pt-PT', { day: 'numeric', month: 'short' })
  return `Semana de ${fmt(monday)} a ${fmt(sunday)}`
}

function toISODate(d) {
  return d.toISOString().split('T')[0]
}

export default function UploadPanel() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [file, setFile] = useState(null)
  const [weekValue, setWeekValue] = useState('')
  const [weekStart, setWeekStart] = useState(null)
  const [initials, setInitials] = useState(user?.initials || '')
  const [dragging, setDragging] = useState(false)
  const [parsing, setParsing] = useState(false)
  const [parsedSessions, setParsedSessions] = useState(null)
  const [exportedCount, setExportedCount] = useState(null)
  const [toast, setToast] = useState(null)
  const fileInputRef = useRef()

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  const noInitials = !initials || initials.trim() === ''

  const handleDrop = (e) => {
    e.preventDefault()
    setDragging(false)
    const f = e.dataTransfer.files[0]
    if (f && f.name.endsWith('.xlsx')) setFile(f)
  }

  const handleWeekChange = (e) => {
    const val = e.target.value
    setWeekValue(val)
    if (val) setWeekStart(toMonday(val))
    else setWeekStart(null)
  }

  const handleParse = async () => {
    if (!file || !weekStart || !initials.trim()) return
    setParsing(true)
    const formData = new FormData()
    formData.append('file', file)
    formData.append('weekStart', toISODate(weekStart))
    try {
      const res = await api.post('/api/timetable/upload', formData)
      if (res.data.length === 0) {
        showToast(`Nenhuma sessão encontrada para as iniciais "${initials}".`, 'error')
      } else {
        setParsedSessions(res.data.map(s => ({ ...s, selected: true })))
      }
    } catch (err) {
      showToast('Erro ao processar o ficheiro.', 'error')
    } finally {
      setParsing(false)
    }
  }

  const handleExport = async (selectedSessions) => {
    try {
      const res = await api.post('/api/timetable/export', {
        weekStart: toISODate(weekStart),
        sessions: selectedSessions,
      })
      setExportedCount(res.data.exported)
    } catch (err) {
      showToast('Erro ao exportar para o Google Calendar.', 'error')
    }
  }

  // Step 3 — Done
  if (exportedCount !== null) {
    return (
      <div className="upload-page">
        <div className="upload-done">
          <div className="done-icon">✅</div>
          <h2>{exportedCount} sessões exportadas para o Google Calendar!</h2>
          <button className="btn btn-primary" onClick={() => navigate('/dashboard')}>
            Ver semana no dashboard
          </button>
        </div>
        {toast && <Toast message={toast.msg} type={toast.type} />}
      </div>
    )
  }

  // Step 2 — Preview
  if (parsedSessions) {
    return (
      <div className="upload-page">
        <div className="upload-header">
          <button className="btn btn-secondary" onClick={() => setParsedSessions(null)}>
            ← Voltar
          </button>
          <h1>Pré-visualização</h1>
        </div>
        <SessionsPreview
          sessions={parsedSessions}
          weekStart={weekStart}
          onExport={handleExport}
          onBack={() => setParsedSessions(null)}
        />
        {toast && <Toast message={toast.msg} type={toast.type} />}
      </div>
    )
  }

  // Step 1 — Upload
  return (
    <div className="upload-page">
      <div className="upload-container">
        <div className="upload-header">
          <button className="btn btn-secondary" onClick={() => navigate('/dashboard')}>
            ← Dashboard
          </button>
          <h1>Upload de Horário</h1>
        </div>

        {noInitials && (
          <div className="banner-warning">
            Por favor define as tuas iniciais no perfil antes de fazer upload.
            <button className="btn-link" onClick={() => navigate('/profile')}>Ir ao perfil →</button>
          </div>
        )}

        <div className="upload-card">
          {/* Drop zone */}
          <div
            className={`dropzone ${dragging ? 'dragging' : ''} ${file ? 'has-file' : ''}`}
            onDragOver={e => { e.preventDefault(); setDragging(true) }}
            onDragLeave={() => setDragging(false)}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx"
              style={{ display: 'none' }}
              onChange={e => setFile(e.target.files[0])}
            />
            {file ? (
              <>
                <span className="dropzone-icon">📄</span>
                <p className="dropzone-filename">{file.name}</p>
                <p className="dropzone-hint">Clica para substituir</p>
              </>
            ) : (
              <>
                <span className="dropzone-icon">📂</span>
                <p className="dropzone-text">Arrasta o ficheiro .xlsx aqui</p>
                <p className="dropzone-hint">ou clica para selecionar</p>
              </>
            )}
          </div>

          <div className="form-group">
            <label>Semana (escolhe qualquer dia da semana)</label>
            <input
              type="date"
              value={weekValue}
              onChange={handleWeekChange}
              className="week-input"
            />
            {weekStart && (
              <p className="form-hint">{formatWeekLabel(weekStart)}</p>
            )}
          </div>

          <div className="form-group">
            <label>Iniciais</label>
            <input
              type="text"
              value={initials}
              onChange={e => setInitials(e.target.value.toUpperCase())}
              placeholder="Ex: HA"
              maxLength={5}
            />
          </div>

          <button
            className="btn btn-primary full-width"
            disabled={!file || !weekStart || !initials.trim() || parsing}
            onClick={handleParse}
          >
            {parsing ? 'A processar...' : 'Analisar as minhas sessões'}
          </button>
        </div>
      </div>
      {toast && <Toast message={toast.msg} type={toast.type} />}
    </div>
  )
}
