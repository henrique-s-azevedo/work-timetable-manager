import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api'
import WeeklyCalendar from '../components/WeeklyCalendar'
import EditSessionModal from '../components/EditSessionModal'
import Toast from '../components/Toast'
import ConfirmDialog from '../components/ConfirmDialog'
import { exportToPDF } from '../utils/pdfExport'
import './Dashboard.css'

const DISPLAY_NAMES = {
  SALA_MUSCULACAO: 'Sala de Musculação',
  SOBREPOSICAO_SALA: 'Sobreposição de Sala',
  AVALIACAO_FISICA: 'Avaliação Física',
  PERSONAL_TRAINING: 'Personal Training',
  TRANSICAO: 'Transição',
  ADMINISTRATIVO: 'Administrativo',
  VIGILANCIA_PISCINA: 'Vigilância de Piscina',
  NATACAO: 'Natação',
  AULAS_GRUPO: 'Aulas de Grupo',
}

const DAYS = [
  { label: 'Segunda',  value: '1' },
  { label: 'Terça',    value: '2' },
  { label: 'Quarta',   value: '3' },
  { label: 'Quinta',   value: '4' },
  { label: 'Sexta',    value: '5' },
  { label: 'Sábado',   value: '6' },
  { label: 'Domingo',  value: '0' },
]

function toMonday(dateStr) {
  // dateStr: "2026-W25"
  const [year, week] = dateStr.split('-W').map(Number)
  const jan4 = new Date(year, 0, 4)
  const dayOfWeek = jan4.getDay() || 7
  const weekStart = new Date(jan4)
  weekStart.setDate(jan4.getDate() - (dayOfWeek - 1) + (week - 1) * 7)
  return weekStart
}

function formatDate(d) {
  return d.toLocaleDateString('pt-PT', { day: 'numeric', month: 'short' })
}

function addDays(d, n) {
  const r = new Date(d)
  r.setDate(r.getDate() + n)
  return r
}

function toISODate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export default function Dashboard() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const [weekValue, setWeekValue] = useState('') // "YYYY-Www"
  const [weekStart, setWeekStart] = useState(null)
  const [weekEnd, setWeekEnd] = useState(null)
  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(false)
  const [typeFilter, setTypeFilter] = useState('all')
  const [dayFilter, setDayFilter] = useState('all')
  const [editSession, setEditSession] = useState(null)
  const [toast, setToast] = useState(null)
  const [clearConfirm, setClearConfirm] = useState(false)

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  const loadSessions = useCallback(async (monday) => {
    setLoading(true)
    try {
      const res = await api.get('/api/timetable/sessions', {
        params: { weekStart: toISODate(monday) }
      })
      setSessions(res.data)
    } catch (err) {
      showToast('Erro ao carregar sessões.', 'error')
    } finally {
      setLoading(false)
    }
  }, [])

  const handleWeekChange = (e) => {
    const val = e.target.value
    if (!val) return
    setWeekValue(val)
    const monday = toMonday(val)
    const sunday = addDays(monday, 6)
    setWeekStart(monday)
    setWeekEnd(sunday)
    loadSessions(monday)
  }

  const metrics = Object.entries(DISPLAY_NAMES).map(([type, name]) => ({
    type,
    name,
    count: sessions.filter(s => s.sessionType === type).length,
  }))

  const exportedCount = sessions.filter(s => s.exportedToCalendar).length

  const handleClearWeek = async () => {
    setClearConfirm(false)
    try {
      await api.delete(`/api/timetable/weeks/${toISODate(weekStart)}`)
      setSessions([])
      showToast('Semana limpa com sucesso.')
    } catch (err) {
      showToast('Erro ao limpar a semana.', 'error')
    }
  }

  const handleSessionSave = (updated) => {
    setSessions(prev => prev.map(s => s.id === updated.id ? updated : s))
    setEditSession(null)
    showToast('Sessão atualizada.')
  }

  const handleSessionDelete = (id) => {
    setSessions(prev => prev.filter(s => s.id !== id))
    setEditSession(null)
    showToast('Sessão eliminada.')
  }

  const handlePDFExport = () => {
    if (!weekStart) return
    exportToPDF(sessions, weekStart, weekEnd)
  }

  const filteredSessions = sessions.filter(s => {
    if (typeFilter !== 'all' && s.sessionType !== typeFilter) return false
    if (dayFilter !== 'all') {
      const dayIdx = new Date(s.sessionDate + 'T00:00:00').getDay()
      if (String(dayIdx) !== dayFilter) return false
    }
    return true
  })

  const hasExported = sessions.some(s => s.exportedToCalendar)

  const weekLabel = weekStart && weekEnd
    ? `${formatDate(weekStart)} → ${formatDate(weekEnd)} ${weekEnd.getFullYear()}`
    : ''

  return (
    <div className="dashboard">
      {/* Top bar */}
      <header className="topbar">
        <div className="topbar-left">
          <span className="app-title">📅 Timetable</span>
          <div className="week-picker-wrap">
            <input
              type="week"
              value={weekValue}
              onChange={handleWeekChange}
              className="week-input"
            />
            {weekLabel && <span className="week-label">{weekLabel}</span>}
          </div>
          <select
            className="filter-select"
            value={typeFilter}
            onChange={e => setTypeFilter(e.target.value)}
          >
            <option value="all">Filtrar</option>
            {Object.entries(DISPLAY_NAMES).map(([type, name]) => (
              <option key={type} value={type}>{name}</option>
            ))}
          </select>
        </div>
        <div className="topbar-right">
          <button
            className="btn btn-secondary"
            onClick={() => navigate('/upload')}
          >
            ↑ Upload Ficheiro
          </button>
          <button
            className="btn btn-primary"
            disabled={sessions.length === 0}
            onClick={handlePDFExport}
          >
            Exportar PDF
          </button>
          {hasExported && (
            <button
              className="btn btn-danger"
              onClick={() => setClearConfirm(true)}
            >
              Limpar Semana
            </button>
          )}
        </div>
      </header>

      <div className="dashboard-body">
        {/* Sidebar */}
        <aside className="sidebar">
          <div className="metrics">
            <p className="metrics-title">Esta semana</p>
            {metrics.map(m => (
              <button
                key={m.type}
                className={`metric-row ${typeFilter === m.type ? 'active' : ''}`}
                onClick={() => setTypeFilter(prev => prev === m.type ? 'all' : m.type)}
              >
                <span className="metric-name">{m.name}</span>
                <span className="metric-count">{m.count}</span>
              </button>
            ))}
          </div>

          <div className="day-filter">
            <label className="day-filter-label">Dia</label>
            <select
              className="filter-select full-width"
              value={dayFilter}
              onChange={e => setDayFilter(e.target.value)}
            >
              <option value="all">Todos</option>
              {DAYS.map(d => (
                <option key={d.value} value={d.value}>{d.label}</option>
              ))}
            </select>
          </div>

          <button
            className="profile-btn"
            onClick={() => navigate('/profile')}
            title="Perfil"
          >
            <div className="profile-avatar-sm">
              {user?.profilePhotoUrl
                ? <img src={user.profilePhotoUrl} alt="" />
                : <span>{(user?.name || 'U')[0]}</span>
              }
            </div>
            <span>{user?.name || 'Perfil'}</span>
          </button>
        </aside>

        {/* Main calendar area */}
        <main className="calendar-area">
          {loading ? (
            <div className="calendar-empty">A carregar...</div>
          ) : !weekStart ? (
            <div className="calendar-empty">
              <p>Seleciona uma semana para ver as sessões.</p>
            </div>
          ) : (
            <WeeklyCalendar
              sessions={filteredSessions}
              weekStart={weekStart}
              weekEnd={weekEnd}
              onSessionClick={setEditSession}
            />
          )}
        </main>
      </div>

      {editSession && (
        <EditSessionModal
          session={editSession}
          onSave={handleSessionSave}
          onDelete={handleSessionDelete}
          onClose={() => setEditSession(null)}
        />
      )}

      {clearConfirm && (
        <ConfirmDialog
          message={`Tens a certeza? Esta ação irá eliminar ${exportedCount} evento(s) do teu Google Calendar para a semana de ${weekLabel}.`}
          onConfirm={handleClearWeek}
          onCancel={() => setClearConfirm(false)}
        />
      )}

      {toast && <Toast message={toast.msg} type={toast.type} />}
    </div>
  )
}
