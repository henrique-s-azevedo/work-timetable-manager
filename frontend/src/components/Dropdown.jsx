/**
 * Generic trigger+panel dropdown used by the mobile header (type filter, burger
 * menu) and mobile footer (day filter). Owns its own open/close state, closes on
 * outside click and Escape.
 *
 * Props:
 * - trigger {ReactNode}   — content rendered inside the trigger button.
 * - children {ReactNode|Function} — panel content; if a function, called with
 *   { close } so items can close the panel after selection.
 * - align {'left'|'right'} — panel horizontal alignment relative to the trigger.
 * - anchor {'bottom'|'top'} — whether the panel opens below or above the trigger
 *   (footer dropdowns use 'top' since the trigger sits at the bottom of the screen).
 */
import { useEffect, useRef, useState } from 'react'
import './Dropdown.css'

export default function Dropdown({
  trigger,
  children,
  align = 'right',
  anchor = 'bottom',
  className = '',
  triggerClassName = '',
  panelClassName = '',
  triggerLabel,
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef(null)

  useEffect(() => {
    if (!open) return
    const handlePointer = (e) => {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpen(false)
    }
    const handleKey = (e) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', handlePointer)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handlePointer)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  const close = () => setOpen(false)

  return (
    <div className={`dropdown ${className}`} ref={rootRef}>
      <button
        type="button"
        className={`dropdown-trigger ${triggerClassName}`}
        onClick={() => setOpen(o => !o)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={triggerLabel}
      >
        {trigger}
      </button>
      {open && (
        <div className={`dropdown-panel dropdown-panel-${anchor} dropdown-panel-${align} ${panelClassName}`}>
          {typeof children === 'function' ? children({ close }) : children}
        </div>
      )}
    </div>
  )
}
