package com.gymtimetable;

import com.gymtimetable.dto.ParsedSessionDTO;
import com.gymtimetable.service.TimetableParserService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimetableParserService}.
 *
 * <p>Tests are divided into two categories:</p>
 * <ol>
 *   <li><strong>Static map tests</strong> — verify that the abbreviation lookup tables
 *       are complete and correctly mapped. These act as regression guards against accidental
 *       removal or misspelling of entries.</li>
 *   <li><strong>Parsing tests</strong> — build minimal synthetic {@code .xlsx} workbooks
 *       in memory using Apache POI and assert the parser's output. This approach exercises
 *       the full parsing pipeline (including time calculation, day routing, and overlap
 *       detection) without relying on external fixture files.</li>
 * </ol>
 *
 * <p><strong>Note:</strong> Several tests reference {@code TimetableParserService.getDayBlocks()},
 * which returns the hardcoded fallback column ranges used when dynamic Row-1 discovery
 * fails. The helper workbooks written by these tests do not include Row-1 day headers,
 * so the parser falls back to the hardcoded blocks for all parsing tests.</p>
 */
class TimetableParserServiceTest {

    private TimetableParserService service;

    /** A known Monday, used as the {@code weekStart} argument across all parsing tests. */
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 16); // Monday

    @BeforeEach
    void setUp() {
        service = new TimetableParserService();
    }

    // --- Static map tests ---

    /**
     * Verifies that every expected Excel abbreviation has an entry in the display name map.
     * A missing entry would cause sessions of that type to fall back to the raw abbreviation
     * in the UI and Calendar event titles.
     */
    @Test
    void allAbbreviationsHaveDisplayNames() {
        Map<String, String> map = TimetableParserService.getAbbrevToDisplay();
        assertThat(map).containsKeys("IT", "SP", "AF", "PT", "TR", "AD", "VG", "NT", "AG", "AI", "CF", "TRB");
    }

    /**
     * Verifies that every abbreviation maps to the correct {@code SessionType} enum name.
     * Mismatches would cause {@link com.gymtimetable.model.TimetableSession.SessionType#valueOf}
     * to throw during export, preventing sessions from being persisted.
     */
    @Test
    void allAbbreviationsHaveSessionTypes() {
        Map<String, String> types = TimetableParserService.getAbbrevToSessionType();
        assertThat(types.get("IT")).isEqualTo("SALA_MUSCULACAO");
        assertThat(types.get("SP")).isEqualTo("SOBREPOSICAO_SALA");
        assertThat(types.get("AF")).isEqualTo("AVALIACAO_FISICA");
        assertThat(types.get("PT")).isEqualTo("PERSONAL_TRAINING");
        assertThat(types.get("TR")).isEqualTo("TRANSICAO");
        assertThat(types.get("AD")).isEqualTo("ADMINISTRATIVO");
        assertThat(types.get("VG")).isEqualTo("VIGILANCIA_PISCINA");
        assertThat(types.get("NT")).isEqualTo("NATACAO");
        assertThat(types.get("AG")).isEqualTo("AULAS_GRUPO");
        assertThat(types.get("AI")).isEqualTo("AULAS_GRUPO");
        assertThat(types.get("CF")).isEqualTo("AULAS_GRUPO");
        assertThat(types.get("TRB")).isEqualTo("AULAS_GRUPO");
    }

    /**
     * Verifies that every abbreviation maps to the expected Google Calendar color ID.
     * Color IDs control the visual appearance of events in Google Calendar (1–11).
     */
    @Test
    void colorIdsAreCorrect() {
        Map<String, Integer> colors = TimetableParserService.getAbbrevToColor();
        assertThat(colors.get("IT")).isEqualTo(11);
        assertThat(colors.get("SP")).isEqualTo(11);
        assertThat(colors.get("AF")).isEqualTo(6);
        assertThat(colors.get("PT")).isEqualTo(2);
        assertThat(colors.get("TR")).isEqualTo(8);
        assertThat(colors.get("AD")).isEqualTo(4);
        assertThat(colors.get("VG")).isEqualTo(7);
        assertThat(colors.get("NT")).isEqualTo(7);
        assertThat(colors.get("AG")).isEqualTo(1);
        assertThat(colors.get("AI")).isEqualTo(1);
        assertThat(colors.get("CF")).isEqualTo(1);
        assertThat(colors.get("TRB")).isEqualTo(1);
    }

    /**
     * Verifies the hardcoded day-block column ranges match the known gym timetable template layout.
     * Each entry is {@code [dayOffset, startCol, endCol]} where dayOffset is 0-based from Monday.
     */
    @Test
    void dayBlocksHaveCorrectColumnRanges() {
        int[][] blocks = TimetableParserService.getDayBlocks();
        assertThat(blocks.length).isEqualTo(7);
        // Mon: F(5) to AX(49)
        assertThat(blocks[0]).containsExactly(0, 5, 49);
        // Tue: AY(50) to CP(93)
        assertThat(blocks[1]).containsExactly(1, 50, 93);
        // Wed: CQ(94) to EK(140)
        assertThat(blocks[2]).containsExactly(2, 94, 140);
        // Thu: EL(141) to GC(184)
        assertThat(blocks[3]).containsExactly(3, 141, 184);
        // Fri: GD(185) to HU(228)
        assertThat(blocks[4]).containsExactly(4, 185, 228);
        // Sat: HV(229) to JM(272)
        assertThat(blocks[5]).containsExactly(5, 229, 272);
        // Sun: JN(273) to LE(316)
        assertThat(blocks[6]).containsExactly(6, 273, 316);
    }

    // --- Parsing tests using synthetic workbook ---

    /**
     * Verifies that a single IT session occupying two rows (06:00–06:30) is parsed with
     * correct date, time, session type, display name, and color ID.
     */
    @Test
    void parsesSessionWithCorrectTimeCalculation() throws Exception {
        // Build a minimal workbook: IT session for "HA" on Monday, rows 4–5 (06:00–06:30)
        byte[] xlsx = buildWorkbook("HA", "IT", 0, 4, 6); // dayBlock 0 (Mon), startRow 4, 2 rows
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");

        assertThat(result).hasSize(1);
        ParsedSessionDTO s = result.get(0);
        assertThat(s.getSessionDate()).isEqualTo(WEEK_START); // Monday
        assertThat(s.getStartTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(6, 30));
        assertThat(s.getSessionTypeAbbrev()).isEqualTo("IT");
        assertThat(s.getSessionType()).isEqualTo("SALA_MUSCULACAO");
        assertThat(s.getDisplayName()).isEqualTo("Sala de Musculação");
        assertThat(s.getGoogleCalendarColorId()).isEqualTo(11);
    }

    /**
     * Verifies that the day offset is correctly applied: a session placed in the Wednesday
     * column block (dayBlock index 2) must land on {@code WEEK_START + 2 days}.
     */
    @Test
    void parsesSessionOnCorrectDay() throws Exception {
        // Wednesday (dayBlock index 2), same time
        byte[] xlsx = buildWorkbook("HA", "PT", 2, 4, 4);
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSessionDate()).isEqualTo(WEEK_START.plusDays(2)); // Wednesday
        assertThat(result.get(0).getSessionTypeAbbrev()).isEqualTo("PT");
    }

    /**
     * Verifies that initials matching is case-insensitive: lowercase {@code "ha"} in the
     * workbook should match the uppercase {@code "HA"} passed by the caller.
     */
    @Test
    void initialsAreCaseInsensitive() throws Exception {
        byte[] xlsx = buildWorkbook("ha", "IT", 0, 4, 4);
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");
        assertThat(result).hasSize(1);
    }

    /**
     * Verifies that when the requested initials ({@code "HA"}) are not present in the workbook
     * (which only contains {@code "RF"}), the parser returns an empty list without throwing.
     *
     * <p>Note: this relies on the pre-check in {@code initialsExistInSheet} passing because
     * {@code "HA"} was written in the workbook (the test helper writes the initials). If the
     * initials were entirely absent, an {@link IllegalArgumentException} would be thrown instead.</p>
     */
    @Test
    void nonMatchingInitialsReturnEmpty() throws Exception {
        byte[] xlsx = buildWorkbook("RF", "IT", 0, 4, 4);
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");
        assertThat(result).isEmpty();
    }

    /**
     * Verifies that two sessions with identical time ranges on the same day are both flagged
     * as overlapping. The workbook is built with two different session types in adjacent
     * columns, both containing the instructor's initials in the same rows.
     */
    @Test
    void overlappingSessionsFlaggedCorrectly() throws Exception {
        // Two sessions for HA on Monday, same time, in different columns (same day block)
        byte[] xlsx = buildOverlapWorkbook("HA", WEEK_START);
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        long overlapping = result.stream().filter(ParsedSessionDTO::isOverlapping).count();
        assertThat(overlapping).isEqualTo(2);
    }

    /**
     * Verifies that two sessions on the same day at non-overlapping times are not flagged.
     * Session 1 runs 06:00–06:30 and Session 2 runs 07:00–07:30, leaving a 30-minute gap.
     */
    @Test
    void nonOverlappingSessions_NotFlagged() throws Exception {
        // Two sessions on the same day but different times
        byte[] xlsx = buildTwoNonOverlapWorkbook("HA");
        List<ParsedSessionDTO> result = service.parse(new ByteArrayInputStream(xlsx), WEEK_START, "HA");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> !s.isOverlapping());
    }

    // --- Helpers ---

    /**
     * Builds a minimal in-memory {@code .xlsx} workbook containing a single session.
     *
     * <p>The workbook has:</p>
     * <ul>
     *   <li>Row 2 (index 1): the session-type abbreviation in the first column of the day block.</li>
     *   <li>Rows {@code startRow} to {@code startRow + numRows - 1}: the instructor's initials
     *       in the same column, representing consecutive 15-minute slots.</li>
     * </ul>
     * <p>No Row-1 day headers are written, so the parser falls back to hardcoded block ranges.</p>
     *
     * @param initials    the initials to write in the data cells
     * @param abbrev      the session-type abbreviation to write in the header row
     * @param dayBlockIdx zero-based index into the hardcoded day blocks (0=Mon … 6=Sun)
     * @param startRow    zero-based row index of the first data cell
     * @param numRows     number of consecutive rows to fill with the initials
     * @return a byte array containing the serialized workbook
     * @throws Exception if workbook creation or serialization fails
     */
    private byte[] buildWorkbook(String initials, String abbrev, int dayBlockIdx, int startRow, int numRows) throws Exception {
        int[][] blocks = TimetableParserService.getDayBlocks();
        int dayOffset = blocks[dayBlockIdx][0];
        int colStart = blocks[dayBlockIdx][1];

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tim");

            // Row 1 (index 1): session type abbreviation in first column of day block
            Row abbrevRow = sheet.createRow(1);
            abbrevRow.createCell(colStart).setCellValue(abbrev);

            // Data rows
            for (int r = startRow; r < startRow + numRows; r++) {
                Row row = getOrCreateRow(sheet, r);
                row.createCell(colStart).setCellValue(initials);
            }

            return toBytes(wb);
        }
    }

    /**
     * Builds a workbook with two session types (IT and PT) in adjacent Monday columns,
     * both containing the same initials in the same rows — producing a guaranteed overlap.
     *
     * @param initials  the initials to write in both columns
     * @param weekStart unused; kept for readability at the call site
     * @return a byte array containing the serialized workbook
     * @throws Exception if workbook creation fails
     */
    private byte[] buildOverlapWorkbook(String initials, LocalDate weekStart) throws Exception {
        int[][] blocks = TimetableParserService.getDayBlocks();
        int colStart = blocks[0][1]; // Monday

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tim");

            Row abbrevRow = sheet.createRow(1);
            // Two different session types in adjacent columns
            abbrevRow.createCell(colStart).setCellValue("IT");
            abbrevRow.createCell(colStart + 1).setCellValue("PT");

            // Both columns have initials in the same rows → overlap
            for (int r = 4; r < 8; r++) {
                Row row = getOrCreateRow(sheet, r);
                row.createCell(colStart).setCellValue(initials);
                row.createCell(colStart + 1).setCellValue(initials);
            }

            return toBytes(wb);
        }
    }

    /**
     * Builds a workbook with two sessions on Monday at non-overlapping times:
     * Session 1 at 06:00–06:30 (IT, rows 4–5) and Session 2 at 07:00–07:30 (PT, rows 8–9).
     *
     * @param initials the initials to write in both sessions' data cells
     * @return a byte array containing the serialized workbook
     * @throws Exception if workbook creation fails
     */
    private byte[] buildTwoNonOverlapWorkbook(String initials) throws Exception {
        int[][] blocks = TimetableParserService.getDayBlocks();
        int colStart = blocks[0][1];

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tim");

            Row abbrevRow = sheet.createRow(1);
            abbrevRow.createCell(colStart).setCellValue("IT");
            abbrevRow.createCell(colStart + 1).setCellValue("PT");

            // Session 1: rows 4–5 (06:00–06:30)
            for (int r = 4; r < 6; r++) {
                getOrCreateRow(sheet, r).createCell(colStart).setCellValue(initials);
            }
            // Session 2: rows 8–10 (07:00–07:30) — no overlap
            for (int r = 8; r < 10; r++) {
                getOrCreateRow(sheet, r).createCell(colStart + 1).setCellValue(initials);
            }

            return toBytes(wb);
        }
    }

    /**
     * Returns the existing row at {@code idx} or creates it if absent.
     * Apache POI does not auto-create rows, so this is required when writing sparse workbooks.
     *
     * @param sheet the target worksheet
     * @param idx   zero-based row index
     * @return the existing or newly created {@link Row}
     */
    private Row getOrCreateRow(Sheet sheet, int idx) {
        Row row = sheet.getRow(idx);
        return row != null ? row : sheet.createRow(idx);
    }

    /**
     * Serializes an {@link XSSFWorkbook} to a byte array for use as an in-memory input stream.
     *
     * @param wb the workbook to serialize
     * @return the workbook bytes
     * @throws Exception if serialization fails
     */
    private byte[] toBytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
