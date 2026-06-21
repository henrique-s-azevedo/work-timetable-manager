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

@RestController
@RequestMapping("/api/timetable")
@RequiredArgsConstructor
public class TimetableController {

    private final TimetableParserService parserService;
    private final GoogleCalendarService calendarService;
    private final InstructorRepository instructorRepository;
    private final TimetableSessionRepository sessionRepository;

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

    private Instructor getInstructor(String googleId) {
        return instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found. Please login first."));
    }

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
