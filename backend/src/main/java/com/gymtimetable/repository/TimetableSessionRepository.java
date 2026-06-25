package com.gymtimetable.repository;

import com.gymtimetable.model.Instructor;
import com.gymtimetable.model.TimetableSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data JPA repository for {@link TimetableSession} entities.
 *
 * <p>All queries are scoped to a specific {@link Instructor} to enforce data isolation
 * — no instructor can access or modify another instructor's sessions.</p>
 */
@Repository
public interface TimetableSessionRepository extends JpaRepository<TimetableSession, Long> {

    /**
     * Returns all sessions belonging to an instructor for a given week, without any ordering.
     *
     * <p>Used by the delete-week endpoint to retrieve the full set of sessions before
     * issuing individual Google Calendar delete calls followed by a bulk DB deletion.</p>
     *
     * @param instructor   the owning instructor
     * @param weekStartDate the Monday of the target ISO week
     * @return unordered list of matching sessions; empty if none exist
     */
    List<TimetableSession> findByInstructorAndWeekStartDate(Instructor instructor, LocalDate weekStartDate);

    /**
     * Returns only the sessions that have been successfully exported to Google Calendar
     * for a given instructor and week.
     *
     * <p>Reserved for cases where the application needs to distinguish between sessions
     * that exist in the database but have not yet produced a calendar event.</p>
     *
     * @param instructor   the owning instructor
     * @param weekStartDate the Monday of the target ISO week
     * @return list of exported sessions; empty if none exist
     */
    List<TimetableSession> findByInstructorAndWeekStartDateAndExportedToCalendarTrue(Instructor instructor, LocalDate weekStartDate);

    /**
     * Returns all sessions for a given instructor and week, sorted chronologically.
     *
     * <p>Used by the dashboard's session-list endpoint to present sessions in display order
     * without requiring an in-memory sort on the client.</p>
     *
     * @param instructor   the owning instructor
     * @param weekStartDate the Monday of the target ISO week
     * @return sessions ordered by {@code sessionDate} ascending, then {@code startTime} ascending
     */
    List<TimetableSession> findByInstructorAndWeekStartDateOrderBySessionDateAscStartTimeAsc(Instructor instructor, LocalDate weekStartDate);

    /**
     * Deletes all sessions belonging to an instructor for a given week from the database.
     *
     * <p>Called after Google Calendar events have been removed so that the DB and Calendar
     * remain consistent. Annotating the calling method with {@code @Transactional} is
     * required for this derived-delete to execute correctly.</p>
     *
     * @param instructor   the owning instructor
     * @param weekStartDate the Monday of the target ISO week
     */
    void deleteByInstructorAndWeekStartDate(Instructor instructor, LocalDate weekStartDate);
}
