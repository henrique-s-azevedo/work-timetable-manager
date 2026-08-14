/**
 * Small inline SVG icons for mobile navigation controls (burger menu, filter
 * dropdowns, expand/collapse chevrons). Kept as plain components — no icon
 * library is installed in this project, and these are the only 3 icons needed.
 */

export function BurgerIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <line x1="3" y1="5.5" x2="17" y2="5.5" />
      <line x1="3" y1="10" x2="17" y2="10" />
      <line x1="3" y1="14.5" x2="17" y2="14.5" />
    </svg>
  )
}

export function FilterIcon({ size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 4.5h14L11.5 10.5v5L8.5 17v-6.5L3 4.5Z" />
    </svg>
  )
}

export function ChevronIcon({ size = 16, direction = 'down' }) {
  const rotation = { down: 0, up: 180, left: 90, right: -90 }[direction]
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ transform: `rotate(${rotation}deg)`, transition: 'transform 0.15s' }}
    >
      <polyline points="5,7.5 10,12.5 15,7.5" />
    </svg>
  )
}
