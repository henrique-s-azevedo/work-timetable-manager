package com.gymtimetable.service;

import com.gymtimetable.dto.ParsedSessionDTO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Core parsing service that extracts an instructor's scheduled sessions from a gym
 * timetable Excel workbook ({@code .xlsx} format).
 *
 * <h2>Timetable Structure</h2>
 * <p>The Excel file uses a fixed layout across up to 316 columns (A–LE):</p>
 * <ul>
 *   <li><strong>Row 1</strong> — day headers (e.g., {@code "2ª FEIRA"}, {@code "SÁBADO"})
 *       marking the start column of each day's block.</li>
 *   <li><strong>Rows 2–4</strong> — session-type abbreviations (e.g., {@code "AG"},
 *       {@code "PT"}) in the header band. Row 4 also carries sub-labels within AG columns
 *       (e.g., {@code "E1"}, {@code "RPM"}) that identify the specific studio.</li>
 *   <li><strong>Rows 5–70 (0-based indices 4–69)</strong> — the data grid. Each row
 *       represents a 15-minute time slot starting at 06:00 (row 4) and ending at 22:15
 *       (row 69). Cells contain instructor initials when a session is scheduled.</li>
 * </ul>
 *
 * <h2>Parsing Algorithm</h2>
 * <ol>
 *   <li>Verify the instructor's initials exist somewhere in the data area; throw
 *       {@link IllegalArgumentException} with message {@code "USER_NOT_IN_TT"} if not.</li>
 *   <li>Discover day-column blocks dynamically from Row 1; fall back to hardcoded ranges
 *       if dynamic discovery fails.</li>
 *   <li>Within each day block, build a column→abbreviation map (with carry-forward for
 *       merged header cells) and a column→location map from Row-4 sub-labels.</li>
 *   <li>Scan data rows for consecutive cells matching the initials; each contiguous run
 *       becomes one session with start/end times derived from the row indices.</li>
 *   <li>Post-process: split IT (weight-room) sessions around overlapping AI (ABS class)
 *       slots, then flag any remaining time overlaps.</li>
 * </ol>
 *
 * <p>All static maps are exposed via unmodifiable accessors so that controllers and tests
 * can look up display names, session types, and color IDs without coupling to the service
 * instance lifecycle.</p>
 */
@Service
public class TimetableParserService {

    /**
     * Maps Excel column abbreviations to their Portuguese display names shown in the UI
     * and Google Calendar event titles.
     */
    private static final Map<String, String>  ABBREV_TO_DISPLAY      = new LinkedHashMap<>();

    /**
     * Maps Excel column abbreviations to the string name of the corresponding
     * {@link com.gymtimetable.model.TimetableSession.SessionType} enum constant.
     */
    private static final Map<String, String>  ABBREV_TO_SESSION_TYPE = new LinkedHashMap<>();

    /**
     * Maps Excel column abbreviations to their Google Calendar color IDs (1–11).
     * Colors are assigned per session category to provide visual distinction in the calendar.
     */
    private static final Map<String, Integer> ABBREV_TO_COLOR        = new LinkedHashMap<>();

    /**
     * Maps uppercased Portuguese day-name variants (as they appear in Row 1 of the Excel file)
     * to a zero-based day offset from Monday (0 = Monday, 6 = Sunday).
     * Multiple variants of the same day are included to handle inconsistencies across
     * different versions of the timetable template.
     */
    private static final Map<String, Integer> DAY_TO_OFFSET          = new LinkedHashMap<>();

    /** Maps Row-4 sub-labels within AG column blocks to human-readable location names. */
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

    /** Zero-based row index of the first data row (06:00). Row 1–4 are header rows. */
    private static final int DATA_ROW_START = 4;

    /** Zero-based row index of the last data row (22:15). Each row represents 15 minutes. */
    private static final int DATA_ROW_END   = 69;

    /** Absolute time corresponding to row index {@code DATA_ROW_START}. */
    private static final LocalTime BASE_TIME = LocalTime.of(6, 0);

    /**
     * Abbreviations for which a class name should be extracted from the Excel cell comment.
     * NT is included because swimming lanes may have instructor-annotated class names.
     */
    private static final Set<String> CLASS_NAME_TYPES = Set.of("AG", "AI", "CF", "TRB", "NT");

    /** Abbreviations for which a studio/location sub-label is expected in Row 4. */
    private static final Set<String> LOCATION_TYPES   = Set.of("AG", "AI", "CF", "TRB");

    /**
     * Maximum column index scanned when searching for day headers, abbreviations, and data.
     * Set generously above the known last data column (316) to allow for future template expansions.
     */
    private static final int MAX_COL_SCAN = 400;

    // ── Public parse entry point ───────────────────────────────────────────────

    /**
     * Parses an Excel timetable stream and returns all sessions belonging to the given instructor.
     *
     * <p>The method performs a full scan of the first worksheet, discovers day-column blocks,
     * and collects every contiguous run of cells matching {@code initials} as a timed session.
     * After collection, IT sessions are split around any AI slots on the same day, and all
     * pairwise time overlaps are flagged.</p>
     *
     * @param excelStream an {@link InputStream} of a valid {@code .xlsx} workbook
     * @param weekStart   the Monday of the ISO week being parsed; used to compute absolute
     *                    session dates from the day-offset discovered per column block
     * @param initials    the instructor's Excel abbreviation (case-insensitive, stripped)
     * @return an ordered list of {@link ParsedSessionDTO} objects; never {@code null},
     *         may be empty if the initials are found in the sheet but no sessions exist
     * @throws IllegalArgumentException if {@code initials} cannot be found anywhere in the
     *                                  data area — the exception message is {@code "USER_NOT_IN_TT"}
     * @throws Exception                if the workbook cannot be read or is structurally invalid
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

    /**
     * Splits weight-room (IT) sessions to exclude any time windows occupied by ABS-class (AI) sessions.
     *
     * <p>In the gym model, an instructor who teaches an ABS class (AI) is simultaneously
     * listed in the IT (weight-room) column as present. The IT session therefore needs to
     * be split into up to two fragments — the time before and after the AI slot — so that
     * the calendar does not show a single unbroken IT block that encompasses the class time.</p>
     *
     * <p>If multiple AI sessions overlap a single IT session, the IT block is fragmented
     * around each of them in chronological order. Fragments with zero duration are not
     * added to the result list.</p>
     *
     * @param sessions the full list of parsed sessions; modified in-place by removing
     *                 split IT sessions and adding their replacement fragments
     */
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

    /**
     * Creates a shallow copy of an IT session DTO with overridden start and end times.
     *
     * @param src   the original IT session to clone
     * @param start the new start time for the fragment
     * @param end   the new end time for the fragment
     * @return a new {@link ParsedSessionDTO} with all fields from {@code src} except for
     *         {@code startTime} and {@code endTime}
     */
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

    /**
     * Performs a full scan of the data area to verify that the instructor's initials appear
     * at least once in the sheet.
     *
     * <p>This check runs before the expensive per-column scan so that a {@code 422} response
     * can be returned immediately when the wrong timetable file or wrong initials are
     * submitted, rather than silently returning an empty session list.</p>
     *
     * @param sheet         the first worksheet of the uploaded workbook
     * @param upperInitials the uppercase-trimmed initials to search for
     * @return {@code true} if the initials are found in at least one data cell; {@code false} otherwise
     */
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

    /**
     * Resolves the class name for a session based on its abbreviation type.
     *
     * <p>Resolution rules:</p>
     * <ul>
     *   <li>{@code AI} — always {@code "ABS"}, regardless of cell content or comments.</li>
     *   <li>{@code AG}, {@code CF}, {@code TRB}, {@code NT} — extracted from the Excel cell
     *       comment (second line, after the author line).</li>
     *   <li>All other types — {@code null}, as they do not carry a named class.</li>
     * </ul>
     *
     * @param sheet    the worksheet being parsed
     * @param abbrev   the session-type abbreviation of the column
     * @param startRow the row index of the first cell occupied by the instructor's initials
     * @param col      the column index being processed
     * @return the resolved class name string, or {@code null} if not applicable
     */
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

    /**
     * Dynamically discovers the column ranges for each day's block by scanning Row 1.
     *
     * <p>Each day's block starts at the column where a recognized day-name header appears
     * and ends one column before the next day's header. The last block ends at the rightmost
     * column that contains any known session-type abbreviation in rows 2–4.</p>
     *
     * <p>If Row 1 contains no recognizable day headers (e.g., the template was modified),
     * an empty map is returned and the caller falls back to {@link #hardcodedDayBlocks()}.</p>
     *
     * @param sheet the first worksheet of the workbook
     * @return a {@link LinkedHashMap} from day offset (0=Mon … 6=Sun) to a two-element
     *         {@code int[]} containing {@code [startCol, endCol]}, in day order;
     *         empty if no day headers were found
     */
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

    /**
     * Scans header rows 2–4 (0-based indices 1–3) to find the rightmost column that
     * contains a known session-type abbreviation.
     *
     * <p>This determines the upper bound of the last day's column block when computing
     * day ranges dynamically from Row 1.</p>
     *
     * @param sheet the first worksheet
     * @return the maximum column index containing a known abbreviation; {@code 0} if none found
     */
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

    /**
     * Builds a map from column index to session-type abbreviation for a single day block.
     *
     * <p>The timetable uses merged header cells, so an abbreviation written in column N
     * applies to columns N, N+1, … until the next distinct abbreviation. This method
     * implements that carry-forward by tracking the last seen abbreviation and applying it
     * to subsequent columns until a new one is found.</p>
     *
     * <p>Only values present in {@code ABBREV_TO_DISPLAY} are treated as valid abbreviations;
     * other cell content (e.g., lane numbers in NT columns) is ignored.</p>
     *
     * @param sheet    the first worksheet
     * @param colStart the first column of the day block (inclusive)
     * @param colEnd   the last column of the day block (inclusive)
     * @return a map from column index to the abbreviation that governs that column
     */
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

    /**
     * Builds a map from column index to the human-readable location for each group-fitness column.
     *
     * <p>AG/CF/TRB columns use Row 4 to indicate which studio the class takes place in
     * (e.g., {@code "E1"} → {@code "Estúdio 1"}, {@code "RPM"} → {@code "Cycling"}).
     * AI columns are always in {@code "Sala de Musculação"} by convention, regardless of Row 4.</p>
     *
     * <p>Columns whose Row-4 label does not match any entry in {@code AG_SUB_LOCATION} are
     * not included in the result map, so {@code location} will be {@code null} for those sessions.</p>
     *
     * @param sheet       the first worksheet
     * @param colStart    the first column of the day block (inclusive)
     * @param colEnd      the last column of the day block (inclusive)
     * @param colToAbbrev the abbreviation map produced by {@link #buildColAbbrevMap} for this block
     * @return a map from column index to location string for columns that have a known location
     */
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

    /**
     * Retrieves the uppercased, stripped string value of the cell at the given row and column.
     *
     * @param sheet  the worksheet to read from
     * @param rowIdx zero-based row index
     * @param colIdx zero-based column index
     * @return the normalized cell value, or an empty string if the row or cell does not exist
     */
    private String getCellStringValue(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return "";
        return getCellStringValueFromRow(row, colIdx);
    }

    /**
     * Extracts, normalizes, and returns the string value of a cell within an already-fetched row.
     *
     * <p>Handles {@code STRING}, {@code NUMERIC}, and {@code FORMULA} cell types. Numeric
     * values are cast to {@code long} before conversion to avoid trailing decimals from
     * floating-point representation (e.g., cells stored as {@code 2.0} instead of {@code 2}).
     * Formula cells are evaluated as string first, then numeric on failure.</p>
     *
     * <p>Leading single-quote characters (Excel's text prefix marker) are stripped, and the
     * result is uppercased and trimmed for consistent matching against the static maps.</p>
     *
     * @param row    the row containing the cell
     * @param colIdx zero-based column index within the row
     * @return the normalized string value; never {@code null}, returns {@code ""} for empty cells
     */
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

    /**
     * Attempts to extract the fitness class name from the Excel cell comment at the given position.
     *
     * <p>The gym's timetable convention stores the class name as the second line of the
     * cell comment, with the first line being the author's name (ignored). If the comment
     * is absent or the second line is blank, {@code null} is returned.</p>
     *
     * @param sheet  the worksheet to read from
     * @param rowIdx zero-based row index of the cell to inspect
     * @param colIdx zero-based column index of the cell to inspect
     * @return the trimmed class name from the comment, or {@code null} if unavailable
     */
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

    /**
     * Marks sessions as overlapping when two or more sessions on the same day have
     * intersecting time ranges.
     *
     * <p>Both sessions in each overlapping pair are flagged. This is an O(n²) pairwise
     * check, which is acceptable given that the number of sessions per instructor per week
     * is typically small (under 50).</p>
     *
     * @param sessions the list of sessions to check; {@code overlapping} fields are set in-place
     */
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

    /**
     * Returns {@code true} if sessions {@code a} and {@code b} have overlapping time intervals
     * using the standard half-open interval intersection test: {@code a.start < b.end && b.start < a.end}.
     *
     * @param a the first session
     * @param b the second session
     * @return {@code true} if the intervals overlap; {@code false} if they are adjacent or disjoint
     */
    private boolean timesOverlap(ParsedSessionDTO a, ParsedSessionDTO b) {
        return a.getStartTime().isBefore(b.getEndTime())
            && b.getStartTime().isBefore(a.getEndTime());
    }

    // ── Fallback hardcoded blocks ──────────────────────────────────────────────

    /**
     * Returns the hardcoded day-block column ranges based on the known timetable template layout.
     *
     * <p>Used as a fallback when dynamic discovery from Row 1 finds no recognizable day headers.
     * Column ranges were determined by manual inspection of the gym's standard Excel template.</p>
     *
     * @return a map from day offset (0=Mon … 6=Sun) to {@code [startCol, endCol]} arrays
     */
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

    /**
     * Returns an unmodifiable view of the abbreviation → display name map.
     * Used by controllers to populate {@code displayName} on persisted sessions.
     *
     * @return unmodifiable map from Excel abbreviation to Portuguese display name
     */
    public static Map<String, String> getAbbrevToDisplay() {
        return Collections.unmodifiableMap(ABBREV_TO_DISPLAY);
    }

    /**
     * Returns an unmodifiable view of the abbreviation → session type string map.
     * Values correspond to the {@link com.gymtimetable.model.TimetableSession.SessionType} enum names.
     *
     * @return unmodifiable map from Excel abbreviation to session type string
     */
    public static Map<String, String> getAbbrevToSessionType() {
        return Collections.unmodifiableMap(ABBREV_TO_SESSION_TYPE);
    }

    /**
     * Returns an unmodifiable view of the abbreviation → Google Calendar color ID map.
     *
     * @return unmodifiable map from Excel abbreviation to Calendar color ID (1–11)
     */
    public static Map<String, Integer> getAbbrevToColor() {
        return Collections.unmodifiableMap(ABBREV_TO_COLOR);
    }

    /**
     * Returns an unmodifiable view of the day-name → offset map used to parse Row 1 headers.
     *
     * @return unmodifiable map from uppercased Portuguese day name to zero-based day offset
     */
    public static Map<String, Integer> getDayToOffset() {
        return Collections.unmodifiableMap(DAY_TO_OFFSET);
    }

    /**
     * Returns the hardcoded fallback column ranges used when dynamic Row-1 day-header
     * discovery finds no recognisable headers.
     *
     * <p>Each element is a three-element array {@code {dayOffset, startCol, endCol}}
     * (zero-based column indices, inclusive on both ends), ordered Monday through Sunday.</p>
     *
     * @return a 7×3 array of day-block descriptors
     */
    public static int[][] getDayBlocks() {
        return new int[][] {
            {0,   5,  49},
            {1,  50,  93},
            {2,  94, 140},
            {3, 141, 184},
            {4, 185, 228},
            {5, 229, 272},
            {6, 273, 316},
        };
    }
}
