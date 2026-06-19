package com.gymtimetable.repository;

import com.gymtimetable.model.Instructor;
import com.gymtimetable.model.TimetableSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TimetableSessionRepository extends JpaRepository<TimetableSession, Long> {
    List<TimetableSession> findByInstructorAndWeekStartDate(Instructor instructor, LocalDate weekStartDate);
    List<TimetableSession> findByInstructorAndWeekStartDateAndExportedToCalendarTrue(Instructor instructor, LocalDate weekStartDate);
    List<TimetableSession> findByInstructorAndWeekStartDateOrderBySessionDateAscStartTimeAsc(Instructor instructor, LocalDate weekStartDate);
    void deleteByInstructorAndWeekStartDate(Instructor instructor, LocalDate weekStartDate);
}
