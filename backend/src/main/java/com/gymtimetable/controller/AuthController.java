package com.gymtimetable.controller;

import com.gymtimetable.model.Instructor;
import com.gymtimetable.repository.InstructorRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * REST controller responsible for instructor authentication and profile management.
 *
 * <p>All endpoints under {@code /api/auth} handle the lifecycle of an instructor's
 * identity in the system: first-time registration, session restoration, and profile edits.
 * Authentication is performed upstream by {@link com.gymtimetable.config.GoogleTokenAuthenticationFilter};
 * by the time a request reaches this controller the Google user ID is already available
 * via {@code @AuthenticationPrincipal}.</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final InstructorRepository instructorRepository;

    /**
     * Authenticates an instructor and creates their account if it does not yet exist.
     *
     * <p>The frontend calls this endpoint immediately after a successful Google OAuth
     * implicit-flow login, passing the access token and profile data obtained from Google.
     * The endpoint upserts the instructor record and refreshes the stored access token so
     * that subsequent Google Calendar operations use a fresh token.</p>
     *
     * <p>Profile fields (name, photo URL) are only set on the first login and are never
     * overwritten by this endpoint — subsequent profile edits go through
     * {@link #updateProfile(Map, String)}.</p>
     *
     * @param body      request body containing {@code accessToken}, {@code email},
     *                  {@code name}, and {@code picture} fields
     * @param googleId  the authenticated Google user ID, injected by Spring Security
     * @return {@code 200 OK} with a JSON object containing the instructor's profile fields,
     *         or {@code 401 Unauthorized} if the security context could not resolve a Google ID
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal String googleId) {

        if (googleId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).build();
        }

        String email = (String) body.get("email");
        String name = (String) body.get("name");
        String picture = (String) body.get("picture");
        String accessToken = (String) body.get("accessToken");

        // Upsert: load the existing instructor or create a new one with the minimum required fields.
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElse(Instructor.builder().googleId(googleId).email(email).build());

        if (name != null) instructor.setName(name);
        if (email != null && instructor.getEmail() == null) instructor.setEmail(email);
        // Profile photo is only set once; instructors may customize it later via /profile.
        if (picture != null && instructor.getProfilePhotoUrl() == null) instructor.setProfilePhotoUrl(picture);
        if (accessToken != null) instructor.setAccessToken(accessToken);
        // Google access tokens have a 1-hour lifetime; mirror this expiry locally.
        instructor.setTokenExpiry(LocalDateTime.now().plusHours(1));

        instructorRepository.save(instructor);

        return ResponseEntity.ok(Map.of(
            "id", instructor.getId(),
            "email", instructor.getEmail() != null ? instructor.getEmail() : "",
            "name", instructor.getName() != null ? instructor.getName() : "",
            "initials", instructor.getInitials() != null ? instructor.getInitials() : "",
            "profilePhotoUrl", instructor.getProfilePhotoUrl() != null ? instructor.getProfilePhotoUrl() : ""
        ));
    }

    /**
     * Returns the current instructor's profile data.
     *
     * <p>Called by the frontend on application startup to restore the user object from
     * a previously stored token without requiring a full re-login flow.</p>
     *
     * @param googleId the authenticated Google user ID
     * @return {@code 200 OK} with the instructor's profile, or {@code 500} if the
     *         instructor record does not exist (should not happen if login was called first)
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal String googleId) {
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));
        return ResponseEntity.ok(toMap(instructor));
    }

    /**
     * Updates the instructor's timetable initials.
     *
     * <p>The initials are stripped of surrounding whitespace and forced to uppercase before
     * persistence to ensure consistent matching against the Excel cell values, which are
     * also uppercased during parsing.</p>
     *
     * @param body     request body containing the {@code initials} string (up to 5 characters)
     * @param googleId the authenticated Google user ID
     * @return {@code 200 OK} with the updated instructor profile
     */
    @PatchMapping("/initials")
    public ResponseEntity<Map<String, Object>> updateInitials(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));
        instructor.setInitials(body.get("initials").strip().toUpperCase());
        instructorRepository.save(instructor);
        return ResponseEntity.ok(toMap(instructor));
    }

    /**
     * Updates editable profile fields: {@code name} and {@code profilePhotoUrl}.
     *
     * <p>Only the keys present in the request body are applied; missing keys leave the
     * corresponding fields unchanged. The Google e-mail address is never updated here —
     * it is immutable and tied to the Google account.</p>
     *
     * @param body     request body with optional {@code name} and/or {@code profilePhotoUrl} fields
     * @param googleId the authenticated Google user ID
     * @return {@code 200 OK} with the updated instructor profile
     */
    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String googleId) {

        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));
        if (body.containsKey("name")) instructor.setName(body.get("name"));
        if (body.containsKey("profilePhotoUrl")) instructor.setProfilePhotoUrl(body.get("profilePhotoUrl"));
        instructorRepository.save(instructor);
        return ResponseEntity.ok(toMap(instructor));
    }

    /**
     * Converts an {@link Instructor} entity to a plain map suitable for JSON serialization.
     *
     * <p>Null-safe: all nullable string fields are replaced with empty strings so the
     * frontend can read the response without null-checks.</p>
     *
     * @param i the instructor entity to convert
     * @return an immutable map with keys {@code id}, {@code email}, {@code name},
     *         {@code initials}, and {@code profilePhotoUrl}
     */
    private Map<String, Object> toMap(Instructor i) {
        return Map.of(
            "id", i.getId(),
            "email", i.getEmail() != null ? i.getEmail() : "",
            "name", i.getName() != null ? i.getName() : "",
            "initials", i.getInitials() != null ? i.getInitials() : "",
            "profilePhotoUrl", i.getProfilePhotoUrl() != null ? i.getProfilePhotoUrl() : ""
        );
    }
}
