/**
 * Toast component — transient notification banner.
 *
 * Props:
 * - message {string} — the notification text to display.
 * - type {string}    — visual variant; one of 'success' (default) or 'error'.
 *                      Controls the CSS class applied (see index.css).
 *
 * The component is stateless — visibility and auto-dismiss are managed by the
 * parent via conditional rendering (e.g., `{toast && <Toast ... />}`).
 */
export default function Toast({ message, type = 'success' }) {
  return <div className={`toast ${type}`}>{message}</div>
}
