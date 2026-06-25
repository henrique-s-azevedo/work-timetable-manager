package com.gymtimetable.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Persistent entity representing a single work session exported to Google Calendar.
 *
 * <p>Each row in {@code timetable_sessions} corresponds to one calendar event that was
 * confirmed by the instructor during the preview step and successfully pushed to Google
 * Calendar. Sessions are scoped to a {@code weekStartDate}, which allows the application
 * to load and delete entire weeks atomically.</p>
 *
 * <p>The {@code overlapping} flag mirrors the overlap detection computed by
 * {@link com.gymtimetable.service.TimetableParserService} at parse time and is persisted
 * so the dashboard can highlight conflicting events without re-computing them.</p>
 */
@Entity
@Table(name = "timetable_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableSession {

    /** Auto-generated surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The instructor who owns this session. Loaded lazily to avoid N+1 issues. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    /**
     * Monday of the ISO week this session belongs to.
     * Used as the primary grouping key when loading or clearing a week.
     */
    @Column(nullable = false)
    private LocalDate weekStartDate;

    /** Calendar date of the specific session (may differ from {@code weekStartDate} for Tue–Sun). */
    @Column(nullable = false)
    private LocalDate sessionDate;

    /** Start time of the session, derived from the row index in the Excel timetable (15-minute slots). */
    @Column(nullable = false)
    private LocalTime startTime;

    /** End time of the session (exclusive), calculated from the last row occupied by the instructor's initials. */
    @Column(nullable = false)
    private LocalTime endTime;

    /**
     * Enum representation of the session type, derived from the column abbreviation in the Excel file.
     * Stored as a string to remain human-readable in the database.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionType sessionType;

    /**
     * Raw column abbreviation from the Excel timetable (e.g., {@code "AG"}, {@code "PT"}).
     * Preserved alongside the enum value to allow the controller to look up display names
     * and color IDs from the static maps in {@link com.gymtimetable.service.TimetableParserService}.
     */
    @Column(nullable = false)
    private String sessionTypeAbbrev;

    /**
     * Optional name of the group-fitness class or PT client.
     * Populated from the cell comment for AG/CF/TRB/NT sessions, forced to {@code "ABS"} for AI,
     * and set during the preview step for PT split sessions.
     */
    private String className;

    /**
     * Optional studio or area where the session takes place (e.g., {@code "Estúdio 1"}, {@code "Cycling"}).
     * Derived from the Row-4 sub-label within the AG column block.
     */
    private String location;

    /** Free-text notes added by the instructor; forwarded to the Google Calendar event description. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Google Calendar event ID returned by the Calendar API after successful event creation.
     * Used for subsequent update and delete operations via the API.
     */
    private String googleEventId;

    /**
     * Whether this session has been successfully pushed to Google Calendar.
     * Defaults to {@code false}; set to {@code true} at export time.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean exportedToCalendar = false;

    /**
     * Whether this session was flagged as overlapping with another session on the same day.
     * Computed by {@link com.gymtimetable.service.TimetableParserService#flagOverlaps} and persisted
     * to allow the dashboard to render overlap indicators without reprocessing.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean overlapping = false;

    /**
     * Enumeration of all supported session types, corresponding directly to the column
     * abbreviations used in the gym's Excel timetable template.
     */
    public enum SessionType {
        /** Weight room / gym floor supervision ({@code IT}). */
        SALA_MUSCULACAO,
        /** Room overlap / shared studio booking ({@code SP}). */
        SOBREPOSICAO_SALA,
        /** Physical fitness assessment ({@code AF}). */
        AVALIACAO_FISICA,
        /** One-on-one personal training ({@code PT}). */
        PERSONAL_TRAINING,
        /** Transition / changeover slot ({@code TR}). */
        TRANSICAO,
        /** Administrative / office time ({@code AD}). */
        ADMINISTRATIVO,
        /** Pool lifeguard duty ({@code VG}). */
        VIGILANCIA_PISCINA,
        /** Swimming lessons ({@code NT}). */
        NATACAO,
        /** Group fitness class — covers AG, AI, CF, and TRB column types. */
        AULAS_GRUPO
    }
}
