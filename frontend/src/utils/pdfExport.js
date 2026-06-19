import jsPDF from 'jspdf'
import autoTable from 'jspdf-autotable'

const DAY_NAMES = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb']

function formatDate(d) {
  return d.toLocaleDateString('pt-PT', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

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
