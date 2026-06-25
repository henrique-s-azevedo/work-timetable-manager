package com.gymtimetable.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Data Transfer Object representing a single parsed work session.
 *
 * <p>This DTO is produced by {@link com.gymtimetable.service.TimetableParserService} after
 * scanning the Excel timetable and is returned to the frontend by the upload endpoint.
 * It carries all data the client needs to render the preview calendar, resolve overlaps,
 * and submit an export request.</p>
 *
 * <p>The same type is also used as an intermediate representation when mapping
 * {@link com.gymtimetable.model.TimetableSession} entities back to the controller's JSON
 * responses and when constructing Google Calendar events in
 * {@link com.gymtimetable.service.GoogleCalendarService}.</p>
 *
 * <p>{@code selected} defaults to {@code true} so that all parsed sessions are included in
 * the export unless the instructor explicitly deselects them in the preview UI.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedSessionDTO {

    /**
     * Database ID of the persisted {@link com.gymtimetable.model.TimetableSession}.
     * {@code null} for freshly parsed sessions that have not yet been exported.
     */
    private Long id;

    /** Calendar date of the session (ISO 8601). */
    private LocalDate sessionDate;

    /** Session start time, derived from the 15-minute row index in the Excel file. */
    private LocalTime startTime;

    /** Session end time (exclusive boundary of the last occupied row). */
    private LocalTime endTime;

    /**
     * String name of the {@link com.gymtimetable.model.TimetableSession.SessionType} enum value
     * (e.g., {@code "AULAS_GRUPO"}).
     */
    private String sessionType;

    /**
     * Raw Excel column abbreviation (e.g., {@code "AG"}, {@code "PT"}).
     * Retained to allow color and display-name lookups from the static maps
     * in {@link com.gymtimetable.service.TimetableParserService}.
     */
    private String sessionTypeAbbrev;

    /** Human-readable Portuguese label for the session type (e.g., {@code "Aulas de Grupo"}). */
    private String displayName;

    /**
     * Name of the fitness class or PT client.
     * For AG/CF/TRB/NT sessions this comes from the Excel cell comment.
     * For AI sessions this is always {@code "ABS"}.
     * May be {@code null} for session types that do not carry a class name.
     */
    private String className;

    /**
     * Studio or area where the session takes place (e.g., {@code "Estúdio 1"}, {@code "Cycling"}).
     * Derived from the Row-4 sub-label within AG column blocks. {@code null} for session types
     * without a location.
     */
    private String location;

    /** Optional free-text notes added by the instructor during preview or post-export editing. */
    private String notes;

    /**
     * Google Calendar color ID (1–11) assigned per session type.
     * Forwarded directly to the Calendar API when creating events.
     */
    private int googleCalendarColorId;

    /**
     * Whether this session overlaps in time with another session on the same day.
     * Computed by {@link com.gymtimetable.service.TimetableParserService#flagOverlaps}.
     */
    private boolean overlapping;

    /**
     * Whether the instructor has selected this session for export.
     * Defaults to {@code true}; the frontend may set it to {@code false} in the preview step.
     */
    @Builder.Default
    private boolean selected = true;

    /**
     * Google Calendar event ID of the created event.
     * Populated only after a successful export. {@code null} for unexported sessions.
     */
    private String googleEventId;

    /** Whether this session has been successfully pushed to Google Calendar. */
    private boolean exportedToCalendar;
}
