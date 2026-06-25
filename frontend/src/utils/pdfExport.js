/**
 * PDF export utility for the weekly timetable schedule.
 *
 * Uses jsPDF for document creation and jspdf-autotable for the formatted session table.
 * The output is a landscape A4 PDF downloaded directly to the browser.
 */
import jsPDF from 'jspdf'
import autoTable from 'jspdf-autotable'

/** Short Portuguese day-name labels indexed by getDay() return value (0=Sun … 6=Sat). */
const DAY_NAMES = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb']

/**
 * Formats a Date object as "DD/MM/YYYY" using the Portuguese locale.
 *
 * @param {Date} d - the date to format
 * @returns {string} formatted date string
 */
function formatDate(d) {
  return d.toLocaleDateString('pt-PT', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/**
 * Generates and downloads a landscape A4 PDF containing the weekly schedule table.
 *
 * The table includes all non-overlapping sessions sorted by date then start time.
 * Overlapping sessions are deliberately excluded because they represent unresolved
 * conflicts that should not appear in a printable schedule.
 *
 * Columns: Dia (date + weekday), Horário (time range), Tipo (display name),
 * Aula (class name), Notas (free-text notes).
 *
 * The file is saved as "horario-YYYY-MM-DD.pdf" where the date is the week's Monday.
 *
 * @param {Array}  sessions  - the sessions to include (typically all non-overlapping ones)
 * @param {Date}   weekStart - the Monday of the week (used in the header and filename)
 * @param {Date}   weekEnd   - the Sunday of the week (used in the header subtitle)
 */
export function exportToPDF(sessions, weekStart, weekEnd) {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })

  doc.setFontSize(14)
  doc.setFont('helvetica', 'bold')
  doc.text('Horário Semanal', 14, 14)

  doc.setFontSize(10)
  doc.setFont('helvetica', 'normal')
  doc.text(
    `Semana de ${formatDate(weekStart)} → ${formatDate(weekEnd)}`,
    14, 21
  )

  const rows = sessions
    .filter(s => !s.overlapping)
    .sort((a, b) => {
      if (a.sessionDate !== b.sessionDate) return a.sessionDate.localeCompare(b.sessionDate)
      return a.startTime.localeCompare(b.startTime)
    })
    .map(s => {
      const d = new Date(s.sessionDate + 'T00:00:00')
      return [
        `${DAY_NAMES[d.getDay()]} ${d.getDate()}/${d.getMonth() + 1}`,
        `${s.startTime.slice(0, 5)} – ${s.endTime.slice(0, 5)}`,
        s.displayName || s.sessionTypeAbbrev,
        s.className || '—',
        s.notes || '—',
      ]
    })

  autoTable(doc, {
    head: [['Dia', 'Horário', 'Tipo', 'Aula', 'Notas']],
    body: rows,
    startY: 26,
    styles: { fontSize: 9, cellPadding: 3 },
    headStyles: { fillColor: [79, 110, 247], textColor: 255, fontStyle: 'bold' },
    alternateRowStyles: { fillColor: [245, 246, 250] },
    columnStyles: {
      0: { cellWidth: 30 },
      1: { cellWidth: 30 },
      2: { cellWidth: 55 },
      3: { cellWidth: 55 },
      4: { cellWidth: 'auto' },
    },
  })

  const weekStr = weekStart.toISOString().split('T')[0]
  doc.save(`horario-${weekStr}.pdf`)
}
