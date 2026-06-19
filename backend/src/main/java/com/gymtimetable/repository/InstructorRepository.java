package com.gymtimetable.repository;

import com.gymtimetable.model.Instructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InstructorRepository extends JpaRepository<Instructor, Long> {
    Optional<Instructor> findByGoogleId(String googleId);
    Optional<Instructor> findByEmail(String email);
}
