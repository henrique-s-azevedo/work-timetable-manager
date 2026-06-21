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

    private static final Map<String, String>  ABBREV_TO_DISPLAY      = new LinkedHashMap<>();
    private static final Map<String, String>  ABBREV_TO_SESSION_TYPE = new LinkedHashMap<>();
    private static final Map<String, Integer> ABBREV_TO_COLOR        = new LinkedHashMap<>();
    private static final Map<String, Integer> DAY_TO_OFFSET          = new LinkedHashMap<>();

    // Row-4 sub-labels inside AG columns → readable location
    private static final Map<String, String>  AG_SUB_LOCATION        = new LinkedHashMap<>();

    static {
        ABBREV_TO_DISPLAY.put("IT",  "Sala de Musculação");
        ABBREV_TO_DISPLAY.put("SP",  "Sobreposição de Sala");
        ABBREV_TO_DISPLAY.put("AF",  "Avaliação Física");
        ABBREV_TO_DISPLAY.put("PT",  "Personal Training");
        ABBREV_TO_DISPLAY.put("TR",  "Transição");
        ABBREV_TO_DISPLAY.put("AD",  "Administrativo");
        ABBREV_TO_DISPLAY.put("VG",  "Vigilância de Piscina");
        ABBREV_TO_DISPLAY.put("NT",  "Natação");
        ABBREV_TO_DISPLAY.put("AG",  "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("AI",  "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("CF",  "Aulas de Grupo");
        ABBREV_TO_DISPLAY.put("TRB", "Aulas de Grupo");

        ABBREV_TO_SESSION_TYPE.put("IT",  "SALA_MUSCULACAO");
        ABBREV_TO_SESSION_TYPE.put("SP",  "SOBREPOSICAO_SALA");
        ABBREV_TO_SESSION_TYPE.put("AF",  "AVALIACAO_FISICA");
        ABBREV_TO_SESSION_TYPE.put("PT",  "PERSONAL_TRAINING");
        ABBREV_TO_SESSION_TYPE.put("TR",  "TRANSICAO");
        ABBREV_TO_SESSION_TYPE.put("AD",  "ADMINISTRATIVO");
        ABBREV_TO_SESSION_TYPE.put("VG",  "VIGILANCIA_PISCINA");
        ABBREV_TO_SESSION_TYPE.put("NT",  "NATACAO");
        ABBREV_TO_SESSION_TYPE.put("AG",  "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("AI",  "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("CF",  "AULAS_GRUPO");
        ABBREV_TO_SESSION_TYPE.put("TRB", "AULAS_GRUPO");

        ABBREV_TO_COLOR.put("IT",  11);
        ABBREV_TO_COLOR.put("SP",  11);
        ABBREV_TO_COLOR.put("AF",   6);
        ABBREV_TO_COLOR.put("PT",   2);
        ABBREV_TO_COLOR.put("TR",   8);
        ABBREV_TO_COLOR.put("AD",   4);
        ABBREV_TO_COLOR.put("VG",   7);
        ABBREV_TO_COLOR.put("NT",   7);
        ABBREV_TO_COLOR.put("AG",   1);
        ABBREV_TO_COLOR.put("AI",   1);
        ABBREV_TO_COLOR.put("CF",   1);
        ABBREV_TO_COLOR.put("TRB",  1);

        // Portuguese weekday names as they appear uppercased in Row 1
        DAY_TO_OFFSET.put("2ª FEIRA",      0);
        DAY_TO_OFFSET.put("3ª FEIRA",      1);
        DAY_TO_OFFSET.put("4ª FEIRA",      2);
        DAY_TO_OFFSET.put("5ª FEIRA",      3);
        DAY_TO_OFFSET.put("6ª FEIRA",      4);
        DAY_TO_OFFSET.put("SÁBADO",        5);
        DAY_TO_OFFSET.put("SABADO",        5);
        DAY_TO_OFFSET.put("DOMINGO",       6);
        DAY_TO_OFFSET.put("SEGUNDA",       0);
        DAY_TO_OFFSET.put("SEGUNDA-FEIRA", 0);
        DAY_TO_OFFSET.put("TERCA",         1);
        DAY_TO_OFFSET.put("TERÇA",         1);
        DAY_TO_OFFSET.put("TERÇA-FEIRA",   1);
        DAY_TO_OFFSET.put("QUARTA",        2);
        DAY_TO_OFFSET.put("QUARTA-FEIRA",  2);
        DAY_TO_OFFSET.put("QUINTA",        3);
        DAY_TO_OFFSET.put("QUINTA-FEIRA",  3);
        DAY_TO_OFFSET.put("SEXTA",         4);
        DAY_TO_OFFSET.put("SEXTA-FEIRA",   4);
        DAY_TO_OFFSET.put("SAB",           5);
        DAY_TO_OFFSET.put("DOM",           6);

        // Row-4 sub-labels inside AG columns
        AG_SUB_LOCATION.put("E1",  "Estúdio 1");
        AG_SUB_LOCATION.put("E2",  "Estúdio 2");
        AG_SUB_LOCATION.put("E3",  "Estúdio 3");
        AG_SUB_LOCATION.put("RPM", "Cycling");
        AG_SUB_LOCATION.put("PS",  "Piscina");
    }

    private static final int DATA_ROW_START = 4;   // Row 5 (0-based) = 06:00
    private static final int DATA_ROW_END   = 69;  // Row 70 (0-based) = 22:15
    private static final LocalTime BASE_TIME = LocalTime.of(6, 0);

    // Types that may carry a className from cell comment
    private static final Set<String> CLASS_NAME_TYPES = Set.of("AG", "AI", "CF", "TRB", "NT");
    // Types that carry a location
    private static final Set<String> LOCATION_TYPES   = Set.of("AG", "AI", "CF", "TRB");

    private static final int MAX_COL_SCAN = 400;

    // ── Public parse entry point ───────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException if the user's initials are not found anywhere in the sheet
     */
    public List<ParsedSessionDTO> parse(InputStream excelStream, LocalDate weekStart, String initials) throws Exception {
        List<ParsedSessionDTO> sessions = new ArrayList<>();
        String upperInitials = initials.strip().toUpperCase();

        try (Workbook workbook = new XSSFWorkbook(excelStream)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Quick check: do the initials appear anywhere in the data area?
            if (!initialsExistInSheet(sheet, upperInitials)) {
                throw new IllegalArgumentException("USER_NOT_IN_TT");
            }

            Map<Integer, int[]> dayBlocks = discoverDayBlocks(sheet);
            if (dayBlocks.isEmpty()) {
                dayBlocks = hardcodedDayBlocks();
            }

            for (Map.Entry<Integer, int[]> entry : dayBlocks.entrySet()) {
                int dayOffset   = entry.getKey();
                int colStart    = entry.getValue()[0];
                int colEnd      = entry.getValue()[1];
                LocalDate sessionDate = weekStart.plusDays(dayOffset);

                Map<Integer, String> colToAbbrev   = buildColAbbrevMap(sheet, colStart, colEnd);
                Map<Integer, String> colToLocation  = buildColLocationMap(sheet, colStart, colEnd, colToAbbrev);

                for (int col = colStart; col <= colEnd; col++) {
                    String abbrev = colToAbbrev.get(col);
                    if (abbrev == null) continue;

                    int row = DATA_ROW_START;
                    while (row <= DATA_ROW_END) {
                        String cellVal = getCellStringValue(sheet, row, col);
                        if (cellVal.equalsIgnoreCase(upperInitials)) {
                            int startRow = row;
                            while (row <= DATA_ROW_END
                                    && getCellStringValue(sheet, row, col).equalsIgnoreCase(upperInitials)) {
                                row++;
                            }
                            int endRow = row;

                            LocalTime startTime = BASE_TIME.plusMinutes((long)(startRow - DATA_ROW_START) * 15);
                            LocalTime endTime   = BASE_TIME.plusMinutes((long)(endRow   - DATA_ROW_START) * 15);

                            String className = resolveClassName(sheet, abbrev, startRow, col);
                            String location  = colToLocation.get(col);

                            sessions.add(ParsedSessionDTO.builder()
                                .sessionDate(sessionDate)
                                .startTime(startTime)
                                .endTime(endTime)
                                .sessionType(ABBREV_TO_SESSION_TYPE.getOrDefault(abbrev, "AULAS_GRUPO"))
                                .sessionTypeAbbrev(abbrev)
                                .displayName(ABBREV_TO_DISPLAY.getOrDefault(abbrev, abbrev))
                                .className(className)
                                .location(location)
                                .googleCalendarColorId(ABBREV_TO_COLOR.getOrDefault(abbrev, 1))
                                .overlapping(false)
                                .selected(true)
                                .build());
                        } else {
                            row++;
                        }
                    }
                }
            }
        }

        splitItAroundAi(sessions);
        flagOverlaps(sessions);
        return sessions;
    }

    // ── Split IT sessions around AI time slots ─────────────────────────────────

    private void splitItAroundAi(List<ParsedSessionDTO> sessions) {
        List<ParsedSessionDTO> aiSessions = sessions.stream()
            .filter(s -> "AI".equals(s.getSessionTypeAbbrev()))
            .toList();

        if (aiSessions.isEmpty()) return;

        List<ParsedSessionDTO> toRemove = new ArrayList<>();
        List<ParsedSessionDTO> toAdd    = new ArrayList<>();

        for (ParsedSessionDTO s : sessions) {
            if (!"IT".equals(s.getSessionTypeAbbrev())) continue;

            List<ParsedSessionDTO> overlaps = aiSessions.stream()
                .filter(ai -> ai.getSessionDate().equals(s.getSessionDate())
                           && ai.getStartTime().isBefore(s.getEndTime())
                           && s.getStartTime().isBefore(ai.getEndTime()))
                .sorted(Comparator.comparing(ParsedSessionDTO::getStartTime))
                .toList();

            if (overlaps.isEmpty()) continue;

            toRemove.add(s);
            LocalTime cur = s.getStartTime();
            for (ParsedSessionDTO ai : overlaps) {
                if (cur.isBefore(ai.getStartTime())) {
                    toAdd.add(cloneIt(s, cur, ai.getStartTime()));
                }
                cur = ai.getEndTime();
            }
            if (cur.isBefore(s.getEndTime())) {
                toAdd.add(cloneIt(s, cur, s.getEndTime()));
            }
        }

        sessions.removeAll(toRemove);
        sessions.addAll(toAdd);
    }

    private ParsedSessionDTO cloneIt(ParsedSessionDTO src, LocalTime start, LocalTime end) {
        return ParsedSessionDTO.builder()
            .sessionDate(src.getSessionDate())
            .startTime(start)
            .endTime(end)
            .sessionType(src.getSessionType())
            .sessionTypeAbbrev(src.getSessionTypeAbbrev())
            .displayName(src.getDisplayName())
            .className(src.getClassName())
            .location(src.getLocation())
            .googleCalendarColorId(src.getGoogleCalendarColorId())
            .overlapping(false)
            .selected(true)
            .build();
    }

    // ── Initials presence check ────────────────────────────────────────────────

    private boolean initialsExistInSheet(Sheet sheet, String upperInitials) {
        for (int rowIdx = DATA_ROW_START; rowIdx <= DATA_ROW_END; rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;
            for (int col = 0; col < MAX_COL_SCAN; col++) {
                if (getCellStringValueFromRow(row, col).equalsIgnoreCase(upperInitials)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── className & location resolution ───────────────────────────────────────

    private String resolveClassName(Sheet sheet, String abbrev, int startRow, int col) {
        // AI: always "ABS"
        if ("AI".equals(abbrev)) return "ABS";
        // AG / CF / TRB / NT: try to extract from cell comment
        if (CLASS_NAME_TYPES.contains(abbrev)) {
            return extractClassName(sheet, startRow, col);
        }
        return null;
    }

    // ── Dynamic day-block discovery from Row 1 ────────────────────────────────

    private Map<Integer, int[]> discoverDayBlocks(Sheet sheet) {
        Row row1 = sheet.getRow(0);
        if (row1 == null) return new LinkedHashMap<>();

        List<int[]> dayStarts = new ArrayList<>(); // [dayOffset, startCol]
        String lastDayName = null;

        for (int col = 0; col < MAX_COL_SCAN; col++) {
            String val    = getCellStringValueFromRow(row1, col);
            Integer offset = DAY_TO_OFFSET.get(val);
            if (offset != null && !val.equals(lastDayName)) {
                dayStarts.add(new int[]{offset, col});
                lastDayName = val;
            }
        }

        if (dayStarts.isEmpty()) return new LinkedHashMap<>();

        int lastDataCol = findLastAbbrevCol(sheet);

        Map<Integer, int[]> result = new LinkedHashMap<>();
        for (int i = 0; i < dayStarts.size(); i++) {
            int offset   = dayStarts.get(i)[0];
            int startCol = dayStarts.get(i)[1];
            int endCol   = (i + 1 < dayStarts.size())
                           ? dayStarts.get(i + 1)[1] - 1
                           : lastDataCol;
            result.put(offset, new int[]{startCol, endCol});
        }
        return result;
    }

    private int findLastAbbrevCol(Sheet sheet) {
        int last = 0;
        for (int rowIdx = 1; rowIdx <= 3; rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;
            for (int col = 0; col < MAX_COL_SCAN; col++) {
                if (ABBREV_TO_DISPLAY.containsKey(getCellStringValueFromRow(row, col))) {
                    last = Math.max(last, col);
                }
            }
        }
        return last;
    }

    // ── Abbreviation map (rows 2-4, carry-forward) ────────────────────────────

    private Map<Integer, String> buildColAbbrevMap(Sheet sheet, int colStart, int colEnd) {
        Map<Integer, String> map = new HashMap<>();
        String lastAbbrev = null;

        for (int col = colStart; col <= colEnd; col++) {
            String abbrev = null;
            for (int rowIdx = 1; rowIdx <= 3 && abbrev == null; rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;
                String val = getCellStringValueFromRow(row, col);
                if (!val.isBlank() && ABBREV_TO_DISPLAY.containsKey(val)) {
                    abbrev = val;
                }
            }
            if (abbrev != null) lastAbbrev = abbrev;
            if (lastAbbrev != null) map.put(col, lastAbbrev);
        }
        return map;
    }

    // ── Location map (Row 4 sub-labels for AG-type columns) ───────────────────

    private Map<Integer, String> buildColLocationMap(Sheet sheet, int colStart, int colEnd,
                                                     Map<Integer, String> colToAbbrev) {
        Map<Integer, String> map = new HashMap<>();
        Row row4 = sheet.getRow(3); // Row 4, 0-based index 3

        for (int col = colStart; col <= colEnd; col++) {
            String abbrev = colToAbbrev.get(col);
            if (abbrev == null || !LOCATION_TYPES.contains(abbrev)) continue;

            if ("AI".equals(abbrev)) {
                map.put(col, "Sala de Musculação");
                continue;
            }

            // AG, CF, TRB — read Row 4 sub-label
            if (row4 != null) {
                String label    = getCellStringValueFromRow(row4, col);
                String location = AG_SUB_LOCATION.get(label);
                if (location != null) {
                    map.put(col, location);
                }
            }
        }
        return map;
    }

    // ── Cell-value helpers ─────────────────────────────────────────────────────

    private String getCellStringValue(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return "";
        return getCellStringValueFromRow(row, colIdx);
    }

    private String getCellStringValueFromRow(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx);
        if (cell == null) return "";
        String val = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception e) {
                    try { yield String.valueOf((long) cell.getNumericCellValue()); }
                    catch (Exception ex) { yield ""; }
                }
            }
            default -> "";
        };
        return val.replace("'", "").strip().toUpperCase();
    }

    // ── Class-name from cell comment ───────────────────────────────────────────

    private String extractClassName(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return null;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return null;
        Comment comment = cell.getCellComment();
        if (comment == null) return null;
        String text = comment.getString().getString();
        String[] lines = text.split("\n");
        // Line 1: author (ignore), Line 2: class name
        if (lines.length >= 2) {
            String name = lines[1].strip();
            return name.isBlank() ? null : name;
        }
        return null;
    }

    // ── Overlap detection ──────────────────────────────────────────────────────

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
        return a.getStartTime().isBefore(b.getEndTime())
            && b.getStartTime().isBefore(a.getEndTime());
    }

    // ── Fallback hardcoded blocks ──────────────────────────────────────────────

    private Map<Integer, int[]> hardcodedDayBlocks() {
        Map<Integer, int[]> m = new LinkedHashMap<>();
        m.put(0, new int[]{5,   49});
        m.put(1, new int[]{50,  93});
        m.put(2, new int[]{94,  140});
        m.put(3, new int[]{141, 184});
        m.put(4, new int[]{185, 228});
        m.put(5, new int[]{229, 272});
        m.put(6, new int[]{273, 316});
        return m;
    }

    // ── Static accessors for controller / tests ────────────────────────────────

    public static Map<String, String>  getAbbrevToDisplay()     { return Collections.unmodifiableMap(ABBREV_TO_DISPLAY); }
    public static Map<String, String>  getAbbrevToSessionType() { return Collections.unmodifiableMap(ABBREV_TO_SESSION_TYPE); }
    public static Map<String, Integer> getAbbrevToColor()       { return Collections.unmodifiableMap(ABBREV_TO_COLOR); }
    public static Map<String, Integer> getDayToOffset()         { return Collections.unmodifiableMap(DAY_TO_OFFSET); }
}
