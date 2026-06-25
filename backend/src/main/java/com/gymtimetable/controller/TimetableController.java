package com.gymtimetable.controller;

import com.gymtimetable.dto.ParsedSessionDTO;
import com.gymtimetable.model.Instructor;
import com.gymtimetable.model.TimetableSession;
import com.gymtimetable.model.TimetableSession.SessionType;
import com.gymtimetable.repository.InstructorRepository;
import com.gymtimetable.repository.TimetableSessionRepository;
import com.gymtimetable.service.GoogleCalendarService;
import com.gymtimetable.service.TimetableParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller that manages the timetable upload, export, and session CRUD lifecycle.
 *
 * <p>All endpoints require an authenticated Google user. The authenticated Google ID is
 * resolved to an {@link Instructor} entity at the start of each request. Every data
 * operation is scoped to that instructor — no cross-instructor access is possible.</p>
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>{@code POST /upload} — parse the Excel file and return a preview of sessions.</li>
 *   <li>{@code POST /export} — push selected sessions to Google Calendar and persist them.</li>
 *   <li>{@code GET /sessions} — load persisted sessions for a given week (dashboard view).</li>
 *   <li>{@code PATCH /sessions/{id}} — edit className, location, or notes of one session.</li>
 *   <li>{@code DELETE /sessions/{id}} — delete a single session from DB and Calendar.</li>
 *   <li>{@code DELETE /weeks/{weekStart}} — delete all sessions for a week from DB and Calendar.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/timetable")
@RequiredArgsConstructor
public class TimetableController {

    private final TimetableParserService parserService;
    private final GoogleCalendarService calendarService;
    private final InstructorRepository instructorRepository;
    private final TimetableSessionRepository sessionRepository;

    /**
     * Parses an uploaded Excel timetable and returns the instructor's sessions as a preview.
     *
     * <p>No data is persisted at this stage — the response is intended for the frontend's
     * preview step where the instructor can review, deselect, or edit sessions before
     * committing them to Google Calendar via {@code /export}.</p>
     *
     * <p>Returns {@code 400 Bad Request} if the instructor has not configured their initials.
     * Returns {@code 422 Unprocessable Entity} if the initials are not found in the uploaded file.</p>
     *
     * @param file      the {@code .xlsx} timetable file
     * @param weekStart the Monday of the ISO week contained in the file
     * @param googleId  the authenticated instructor's Google ID
     * @return {@code 200 OK} with the list of parsed sessions, or an error status
     * @throws Exception if the workbook cannot be read
     */
    @PostMapping("/upload")
    public ResponseEntity<List<ParsedSessionDTO>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("weekStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @AuthenticationPrincipal String googleId) throws Exception {

        Instructor instructor = getInstructor(googleId);
        if (instructor.getInitials() == null || instructor.getInitials().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<ParsedSessionDTO> parsed = parserService.parse(file.getInputStream(), weekStart, instructor.getInitials());
            return ResponseEntity.ok(parsed);
        } catch (IllegalArgumentException e) {
            if ("USER_NOT_IN_TT".equals(e.getMessage())) {
                return ResponseEntity.unprocessableEntity().<List<ParsedSessionDTO>>build();
            }
            throw e;
        }
    }

    /**
     * Exports selected sessions to Google Calendar and persists them in the database.
     *
     * <p>Each session in the request body is processed independently. Partial failures
     * (e.g., a Calendar API error for one event) are captured in the {@code errors} list
     * and do not abort the remaining exports — best-effort semantics. The response always
     * contains a count of successfully exported events alongside any error messages.</p>
     *
     * @param body     request body containing {@code weekStart} (ISO date string) and
     *                 {@code sessions} (list of session maps conforming to {@link ParsedSessionDTO})
     * @param googleId the authenticated instructor's Google ID
     * @return {@code 200 OK} with a map containing {@code exported} (count) and {@code errors} (list)
     */
    @PostMapping("/export")
    @Transactional
    public ResponseEntity<Map<String, Object>> export(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = getInstructor(googleId);
        LocalDate weekStart = LocalDate.parse((String) body.get("weekStart"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessionMaps = (List<Map<String, Object>>) body.get("sessions");

        int exported = 0;
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> s : sessionMaps) {
            try {
                ParsedSessionDTO dto = mapToDto(s);
                String googleEventId = calendarService.createEvent(instructor, dto);

                TimetableSession session = TimetableSession.builder()
                    .instructor(instructor)
                    .weekStartDate(weekStart)
                    .sessionDate(dto.getSessionDate())
                    .startTime(dto.getStartTime())
                    .endTime(dto.getEndTime())
                    .sessionType(SessionType.valueOf(dto.getSessionType()))
                    .sessionTypeAbbrev(dto.getSessionTypeAbbrev())
                    .className(dto.getClassName())
                    .location(dto.getLocation())
                    .notes(dto.getNotes())
                    .googleEventId(googleEventId)
                    .exportedToCalendar(true)
                    .overlapping(dto.isOverlapping())
                    .build();

                sessionRepository.save(session);
                exported++;
            } catch (Exception e) {
                errors.add("Session error: " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("exported", exported);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns all persisted sessions for the authenticated instructor in the given week,
     * sorted chronologically.
     *
     * @param weekStart the Monday of the target ISO week
     * @param googleId  the authenticated instructor's Google ID
     * @return {@code 200 OK} with a chronologically ordered list of session maps
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = getInstructor(googleId);
        List<TimetableSession> sessions = sessionRepository
            .findByInstructorAndWeekStartDateOrderBySessionDateAscStartTimeAsc(instructor, weekStart);

        List<Map<String, Object>> result = sessions.stream().map(this::sessionToMap).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Updates editable fields of a persisted session and synchronizes the change to Google Calendar.
     *
     * <p>Only {@code className}, {@code location}, and {@code notes} may be updated — time fields
     * are immutable after export. If the session has a linked Google Calendar event, the event's
     * title and description are updated silently (errors are swallowed to avoid failing the save).</p>
     *
     * <p>Returns {@code 403 Forbidden} if the session does not belong to the authenticated instructor.</p>
     *
     * @param id       the database ID of the session to update
     * @param body     a partial map with optional keys {@code className}, {@code location}, {@code notes}
     * @param googleId the authenticated instructor's Google ID
     * @return {@code 200 OK} with the updated session map, or {@code 403} / {@code 500} on error
     */
    @PatchMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = getInstructor(googleId);
        TimetableSession session = sessionRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getInstructor().getId().equals(instructor.getId())) {
            return ResponseEntity.status(403).build();
        }

        if (body.containsKey("className")) session.setClassName(body.get("className"));
        if (body.containsKey("location")) session.setLocation(body.get("location"));
        if (body.containsKey("notes")) session.setNotes(body.get("notes"));
        sessionRepository.save(session);

        if (session.isExportedToCalendar() && session.getGoogleEventId() != null) {
            try {
                ParsedSessionDTO dto = sessionToDto(session);
                calendarService.updateEvent(instructor, session.getGoogleEventId(), dto);
            } catch (Exception e) {
                // Log but don't fail the request
            }
        }

        return ResponseEntity.ok(sessionToMap(session));
    }

    /**
     * Deletes a single session from the database and removes its Google Calendar event.
     *
     * <p>Calendar deletion errors are swallowed so that a missing or already-deleted
     * Calendar event does not prevent the DB record from being removed.</p>
     *
     * <p>Returns {@code 403 Forbidden} if the session does not belong to the authenticated instructor.</p>
     *
     * @param id       the database ID of the session to delete
     * @param googleId the authenticated instructor's Google ID
     * @return {@code 204 No Content} on success, or {@code 403} / {@code 500} on error
     */
    @DeleteMapping("/sessions/{id}")
    @Transactional
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long id,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = getInstructor(googleId);
        TimetableSession session = sessionRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getInstructor().getId().equals(instructor.getId())) {
            return ResponseEntity.status(403).build();
        }

        if (session.isExportedToCalendar() && session.getGoogleEventId() != null) {
            try {
                calendarService.deleteEvent(instructor, session.getGoogleEventId());
            } catch (Exception e) {
                // Log but still delete from DB
            }
        }

        sessionRepository.delete(session);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes all sessions for a given week from both the database and Google Calendar.
     *
     * <p>Only sessions with {@code exportedToCalendar = true} have associated Calendar events;
     * the count in the response reflects how many Calendar deletions were attempted, not the
     * total number of DB rows removed. Individual Calendar deletion errors are captured in
     * {@code errors} but do not prevent the full DB bulk delete from completing.</p>
     *
     * @param weekStart the Monday of the week to clear
     * @param googleId  the authenticated instructor's Google ID
     * @return {@code 200 OK} with a map containing {@code deleted} (Calendar events removed)
     *         and {@code errors} (list of Calendar deletion error messages)
     */
    @DeleteMapping("/weeks/{weekStart}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteWeek(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = getInstructor(googleId);
        List<TimetableSession> sessions = sessionRepository
            .findByInstructorAndWeekStartDate(instructor, weekStart);

        int deleted = 0;
        List<String> errors = new ArrayList<>();

        for (TimetableSession session : sessions) {
            if (session.isExportedToCalendar() && session.getGoogleEventId() != null) {
                try {
                    calendarService.deleteEvent(instructor, session.getGoogleEventId());
                    deleted++;
                } catch (Exception e) {
                    errors.add("Calendar event deletion failed: " + e.getMessage());
                }
            }
        }

        sessionRepository.deleteByInstructorAndWeekStartDate(instructor, weekStart);

        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    /**
     * Resolves the authenticated principal (Google ID) to an {@link Instructor} entity.
     *
     * <p>Every authenticated endpoint uses this helper to ensure the principal exists in
     * the database before proceeding. Throws if not found — this should not happen under
     * normal operation since the login endpoint creates the record on first access.</p>
     *
     * @param googleId the Google user ID from the security context
     * @return the corresponding {@link Instructor} entity
     * @throws RuntimeException if no instructor with that Google ID exists
     */
    private Instructor getInstructor(String googleId) {
        return instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found. Please login first."));
    }

    /**
     * Converts a {@link TimetableSession} entity to a plain map for JSON serialization.
     *
     * <p>Display name and color ID are looked up from the static maps in
     * {@link TimetableParserService} rather than being stored on the entity, so they stay
     * current if the maps are ever updated without requiring a DB migration.</p>
     *
     * @param s the session entity to serialize
     * @return an ordered map containing all fields needed by the frontend dashboard
     */
    private Map<String, Object> sessionToMap(TimetableSession s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("sessionDate", s.getSessionDate().toString());
        map.put("startTime", s.getStartTime().toString());
        map.put("endTime", s.getEndTime().toString());
        map.put("sessionType", s.getSessionType().name());
        map.put("sessionTypeAbbrev", s.getSessionTypeAbbrev());
        map.put("displayName", TimetableParserService.getAbbrevToDisplay().getOrDefault(s.getSessionTypeAbbrev(), s.getSessionTypeAbbrev()));
        map.put("className", s.getClassName());
        map.put("location", s.getLocation());
        map.put("notes", s.getNotes());
        map.put("googleEventId", s.getGoogleEventId());
        map.put("exportedToCalendar", s.isExportedToCalendar());
        map.put("overlapping", s.isOverlapping());
        map.put("googleCalendarColorId", TimetableParserService.getAbbrevToColor().getOrDefault(s.getSessionTypeAbbrev(), 1));
        return map;
    }

    /**
     * Converts a persisted {@link TimetableSession} to a {@link ParsedSessionDTO} for use
     * in Google Calendar update calls.
     *
     * @param s the session entity to convert
     * @return a DTO populated with all fields required by {@link GoogleCalendarService#updateEvent}
     */
    private ParsedSessionDTO sessionToDto(TimetableSession s) {
        return ParsedSessionDTO.builder()
            .sessionDate(s.getSessionDate())
            .startTime(s.getStartTime())
            .endTime(s.getEndTime())
            .sessionType(s.getSessionType().name())
            .sessionTypeAbbrev(s.getSessionTypeAbbrev())
            .displayName(TimetableParserService.getAbbrevToDisplay().getOrDefault(s.getSessionTypeAbbrev(), s.getSessionTypeAbbrev()))
            .className(s.getClassName())
            .location(s.getLocation())
            .notes(s.getNotes())
            .googleCalendarColorId(TimetableParserService.getAbbrevToColor().getOrDefault(s.getSessionTypeAbbrev(), 1))
            .build();
    }

    /**
     * Deserializes a raw JSON map (received in the export request body) into a {@link ParsedSessionDTO}.
     *
     * <p>The frontend submits sessions as plain JSON objects rather than typed DTOs.
     * This method performs the necessary type-casting and provides safe defaults for
     * optional fields (e.g., {@code className}, {@code location}, {@code notes}).</p>
     *
     * @param m the raw map from the JSON request body
     * @return a fully populated {@link ParsedSessionDTO} ready for Calendar event creation
     */
    private ParsedSessionDTO mapToDto(Map<String, Object> m) {
        return ParsedSessionDTO.builder()
            .sessionDate(LocalDate.parse((String) m.get("sessionDate")))
            .startTime(java.time.LocalTime.parse((String) m.get("startTime")))
            .endTime(java.time.LocalTime.parse((String) m.get("endTime")))
            .sessionType((String) m.get("sessionType"))
            .sessionTypeAbbrev((String) m.get("sessionTypeAbbrev"))
            .displayName((String) m.get("displayName"))
            .className((String) m.getOrDefault("className", null))
            .location((String) m.getOrDefault("location", null))
            .notes((String) m.getOrDefault("notes", null))
            .googleCalendarColorId((Integer) m.getOrDefault("googleCalendarColorId", 1))
            .overlapping(Boolean.TRUE.equals(m.get("overlapping")))
            .build();
    }
}
