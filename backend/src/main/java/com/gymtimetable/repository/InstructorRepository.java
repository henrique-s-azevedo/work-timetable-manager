package com.gymtimetable.repository;

import com.gymtimetable.model.Instructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Instructor} entities.
 *
 * <p>Provides standard CRUD operations inherited from {@link JpaRepository} and two
 * domain-specific lookup methods used during authentication and profile management.</p>
 */
@Repository
public interface InstructorRepository extends JpaRepository<Instructor, Long> {

    /**
     * Finds an instructor by their Google account identifier.
     *
     * <p>This is the primary lookup used by {@link com.gymtimetable.controller.AuthController}
     * and {@link com.gymtimetable.controller.TimetableController} to resolve the authenticated
     * principal (Google user ID) to a database entity.</p>
     *
     * @param googleId the Google {@code sub} claim extracted from the access token
     * @return an {@link Optional} containing the instructor if found, or empty otherwise
     */
    Optional<Instructor> findByGoogleId(String googleId);

    /**
     * Finds an instructor by their Google account e-mail address.
     *
     * <p>Currently reserved for administrative lookups. The primary authentication path
     * uses {@link #findByGoogleId(String)} instead, as the Google ID is more stable than
     * the e-mail address.</p>
     *
     * @param email the instructor's Google account e-mail
     * @return an {@link Optional} containing the instructor if found, or empty otherwise
     */
    Optional<Instructor> findByEmail(String email);
}
