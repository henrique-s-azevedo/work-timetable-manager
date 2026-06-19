import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import api from '../api'
import Toast from '../components/Toast'
import './Profile.css'

export default function Profile() {
  const { user, updateUser, logout } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState(user?.name || '')
  const [initials, setInitials] = useState(user?.initials || '')
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState(null)

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3000)
  }

  const handleSave = async (e) => {
    e.preventDefault()
    setSaving(true)
    try {
      // Update name
      await api.patch('/api/auth/profile', { name })
      // Update initials separately
      await api.patch('/api/auth/initials', { initials })
      updateUser({ name, initials })
      showToast('Perfil atualizado com sucesso.')
    } catch (err) {
      showToast('Erro ao guardar o perfil.', 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="profile-page">
      <div className="profile-container">
        <div className="profile-header">
          <button className="btn btn-secondary back-btn" onClick={() => navigate('/dashboard')}>
            ← Dashboard
          </button>
          <h1>Perfil</h1>
        </div>

        <div className="profile-card">
          <div className="profile-photo-section">
            <div className="profile-avatar">
              {user?.profilePhotoUrl
                ? <img src={user.profilePhotoUrl} alt="Foto de perfil" />
                : <span>{(user?.name || 'U')[0].toUpperCase()}</span>
              }
            </div>
            <div>
              <p className="profile-name">{user?.name}</p>
              <p className="profile-email">{user?.email}</p>
            </div>
          </div>

          <form onSubmit={handleSave} className="profile-form">
            <div className="form-group">
              <label>Nome completo</label>
              <input
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="O teu nome"
              />
            </div>

            <div className="form-group">
              <label>Iniciais do horário</label>
              <input
                type="text"
                value={initials}
                onChange={e => setInitials(e.target.value.toUpperCase())}
                placeholder="Ex: HA"
                maxLength={5}
              />
              <p className="form-hint">Estas iniciais são usadas para identificar as tuas sessões no Excel.</p>
            </div>

            <div className="form-group">
              <label>Conta Google</label>
              <input
                type="text"
                value={user?.email || ''}
                disabled
                className="input-disabled"
              />
              <p className="form-hint">Para alterar, faz logout e entra com outra conta Google.</p>
            </div>

            <div className="profile-actions">
              <button type="submit" className="btn btn-primary" disabled={saving}>
                {saving ? 'A guardar...' : 'Guardar alterações'}
              </button>
              <button type="button" className="btn btn-secondary" onClick={logout}>
                Logout
              </button>
            </div>
          </form>
        </div>
      </div>
      {toast && <Toast message={toast.msg} type={toast.type} />}
    </div>
  )
}
