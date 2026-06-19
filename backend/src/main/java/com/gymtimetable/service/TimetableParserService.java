package com.gymtimetable.service;

import com.gymtimetable.dto.ParsedSessionDTO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
public class TimetableParserService {

    // Session type abbreviation mappings
    private static final Map<String, String> ABBREV_TO_DISPLAY = new LinkedHashMap<>();
    private static final Map<String, String> ABBREV_TO_SESSION_TYPE = new LinkedHashMap<>();
    private static final Map<String, Integer> ABBREV_TO_COLOR = new LinkedHashMap<>();

    static {
        ABBREV_TO_DISPLAY.put("IT", "Sala de Musculação");
        ABBREV_TO_DISPLAY.put("SP", "Sobreposição de Sala");
        ABBREV_TO_DISPLAY.put("AF", "Avaliação Física");
        ABBREV_TO_DISPLAY.put("PT", "Personal Training");
        ABBREV_TO_DISPLAY.put("TR", "Transição");
        ABBREV_TO_DISPLAY.put("AD", "Administrativo");
        ABBREV_TO_DISPLAY.put("VG", "Vigilância de Piscina");
        ABBREV_TO_DISPLAY.put("NT", "Natação");
        ABBREV_TO_DISPLAY.put("AG", "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("AI", "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("CF", "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("TRB", "Aulas de Grupo");

        ABBREV_TO_SESSION_TYPE.put("IT", "SALA_MUSCULACAO");
        ABBREV_TO_SESSION_TYPE.put("SP", "SOBREPOSICAO_SALA");
        ABBREV_TO_SESSION_TYPE.put("AF", "AVALIACAO_FISICA");
        ABBREV_TO_SESSION_TYPE.put("PT", "PERSONAL_TRAINING");
        ABBREV_TO_SESSION_TYPE.put("TR", "TRANSICAO");
        ABBREV_TO_SESSION_TYPE.put("AD", "ADMINISTRATIVO");
        ABBREV_TO_SESSION_TYPE.put("VG", "VIGILANCIA_PISCINA");
        ABBREV_TO_SESSION_TYPE.put("NT", "NATACAO");
        ABBREV_TO_SESSION_TYPE.put("AG", "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("AI", "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("CF", "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("TRB", "AULAS_GRUPO");

        ABBREV_TO_COLOR.put("IT", 11);
        ABBREV_TO_COLOR.put("SP", 11);
        ABBREV_TO_COLOR.put("AF", 6);
        ABBREV_TO_COLOR.put("PT", 2);
        ABBREV_TO_COLOR.put("TR", 8);
        ABBREV_TO_COLOR.put("AD", 4);
        ABBREV_TO_COLOR.put("VG", 7);
        ABBREV_TO_COLOR.put("NT", 7);
        ABBREV_TO_COLOR.put("AG", 1);
        ABBREV_TO_COLOR.put("AI", 1);
        ABBREV_TO_COLOR.put("CF", 1);
        ABBREV_TO_COLOR.put("TRB", 1);
    }

    // Day blocks: {dayOffset, startColIndex (0-based), endColIndex (0-based)}
    private static final int[][] DAY_BLOCKS = {
        {0, 5,   49},   // Mon: F–AX
        {1, 50,  93},   // Tue: AY–CP
        {2, 94,  140},  // Wed: CQ–EK
        {3, 141, 184},  // Thu: EL–GC
        {4, 185, 228},  // Fri: GD–HU
        {5, 229, 272},  // Sat: HV–JM
        {6, 273, 316},  // Sun: JN–LE
    };

    private static final int TIME_COL = 4;       // Col E (0-based)
    private static final int DATA_ROW_START = 4; // Row 5 (0-based)
    private static final int DATA_ROW_END = 69;  // Row 70 (0-based)
    private static final LocalTime BASE_TIME = LocalTime.of(6, 0);
    private static final Set<String> CLASS_NAME_TYPES = Set.of("AG", "AI", "CF", "TRB", "NT");

    public List<ParsedSessionDTO> parse(InputStream excelStream, LocalDate weekStart, String initials) throws Exception {
        List<ParsedSessionDTO> sessions = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(excelStream)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Row index 1 has session type abbreviations
            Row abbrevRow = sheet.getRow(1);

            for (int[] block : DAY_BLOCKS) {
                int dayOffset = block[0];
                int colStart = block[1];
                int colEnd = block[2];
                LocalDate sessionDate = weekStart.plusDays(dayOffset);

                // Build column→abbrev map for this day block
                Map<Integer, String> colToAbbrev = buildColAbbrevMap(abbrevRow, colStart, colEnd);

                // Track which columns we've already processed (to avoid duplicate sessions)
                Set<Integer> processedCols = new HashSet<>();

                for (int col = colStart; col <= colEnd; col++) {
                    String abbrev = colToAbbrev.get(col);
                    if (abbrev == null || processedCols.contains(col)) continue;

                    // Scan data rows to find instructor sessions
                    int row = DATA_ROW_START;
                    while (row <= DATA_ROW_END) {
                        String cellVal = getCellStringValue(sheet, row, col);
                        if (cellVal.equalsIgnoreCase(initials)) {
                            int startRow = row;
                            // Scan downward while the cell matches initials
                            while (row <= DATA_ROW_END && getCellStringValue(sheet, row, col).equalsIgnoreCase(initials)) {
                                row++;
                            }
                            int endRow = row;

                            LocalTime startTime = BASE_TIME.plusMinutes((long)(startRow - DATA_ROW_START) * 15);
                            LocalTime endTime = BASE_TIME.plusMinutes((long)(endRow - DATA_ROW_START) * 15);

                            String className = null;
                            if (CLASS_NAME_TYPES.contains(abbrev)) {
                                className = extractClassName(sheet, startRow, col);
                            }

                            ParsedSessionDTO dto = ParsedSessionDTO.builder()
                                .sessionDate(sessionDate)
                                .startTime(startTime)
                                .endTime(endTime)
                                .sessionType(ABBREV_TO_SESSION_TYPE.getOrDefault(abbrev, "AULAS_GRUPO"))
                                .sessionTypeAbbrev(abbrev)
                                .displayName(ABBREV_TO_DISPLAY.getOrDefault(abbrev, abbrev))
                                .className(className)
                                .googleCalendarColorId(ABBREV_TO_COLOR.getOrDefault(abbrev, 1))
                                .overlapping(false)
                                .selected(true)
                                .build();

                            sessions.add(dto);
                        } else {
                            row++;
                        }
                    }
                }
            }
        }

        flagOverlaps(sessions);
        return sessions;
    }

    private Map<Integer, String> buildColAbbrevMap(Row abbrevRow, int colStart, int colEnd) {
        Map<Integer, String> map = new HashMap<>();
        String lastAbbrev = null;
        if (abbrevRow != null) {
            for (int col = colStart; col <= colEnd; col++) {
                String val = getCellStringValueFromRow(abbrevRow, col);
                if (!val.isBlank()) {
                    String upper = val.strip().toUpperCase();
                    if (ABBREV_TO_DISPLAY.containsKey(upper)) {
                        lastAbbrev = upper;
                    }
                }
                if (lastAbbrev != null) {
                    map.put(col, lastAbbrev);
                }
            }
        }
        return map;
    }

    private String getCellStringValue(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return "";
        return getCellStringValueFromRow(row, colIdx);
    }

    private String getCellStringValueFromRow(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx);
        if (cell == null) return "";
        String val = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception e) { yield String.valueOf((long) cell.getNumericCellValue()); }
            }
            default -> "";
        };
        // Strip trailing apostrophe and whitespace, uppercase
        return val.replace("'", "").strip().toUpperCase();
    }

    private String extractClassName(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return null;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return null;
        Comment comment = cell.getCellComment();
        if (comment == null) return null;
        String text = comment.getString().getString();
        String[] lines = text.split("\n");
        // Line 1: author (IGNORE), Line 2: class name (USE)
        if (lines.length >= 2) {
            String className = lines[1].strip();
            return className.isBlank() ? null : className;
        }
        return null;
    }

    private void flagOverlaps(List<ParsedSessionDTO> sessions) {
        for (int i = 0; i < sessions.size(); i++) {
            for (int j = i + 1; j < sessions.size(); j++) {
                ParsedSessionDTO a = sessions.get(i);
                ParsedSessionDTO b = sessions.get(j);
                if (a.getSessionDate().equals(b.getSessionDate()) && timesOverlap(a, b)) {
                    a.setOverlapping(true);
                    b.setOverlapping(true);
                }
            }
        }
    }

    private boolean timesOverlap(ParsedSessionDTO a, ParsedSessionDTO b) {
        return a.getStartTime().isBefore(b.getEndTime()) && b.getStartTime().isBefore(a.getEndTime());
    }

    // Expose static maps for testing
    public static Map<String, String> getAbbrevToDisplay() { return Collections.unmodifiableMap(ABBREV_TO_DISPLAY); }
    public static Map<String, String> getAbbrevToSessionType() { return Collections.unmodifiableMap(ABBREV_TO_SESSION_TYPE); }
    public static Map<String, Integer> getAbbrevToColor() { return Collections.unmodifiableMap(ABBREV_TO_COLOR); }
    public static int[][] getDayBlocks() { return DAY_BLOCKS; }
}
