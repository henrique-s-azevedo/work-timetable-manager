package com.gymtimetable.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Persistent entity representing a gym instructor registered in the system.
 *
 * <p>An instructor is created or updated on first login via Google OAuth. The entity stores
 * the Google identity, the current OAuth access token (used to call the Google Calendar API
 * on the instructor's behalf), and optional profile data such as name and photo URL.</p>
 *
 * <p>The {@code initials} field is the key identifier used to locate an instructor's rows
 * inside the weekly Excel timetable. It must be set by the user in the profile page before
 * any timetable upload can be processed.</p>
 */
@Entity
@Table(name = "instructors")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Instructor {

    /** Auto-generated surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique Google account identifier ({@code sub} claim from Google's token introspection).
     * Used as the principal in Spring Security's authentication context.
     */
    @Column(unique = true, nullable = false)
    private String googleId;

    /** Google account e-mail address. Unique per instructor and never reassigned. */
    @Column(unique = true, nullable = false)
    private String email;

    /** Display name sourced from Google profile, editable by the instructor in the profile page. */
    private String name;

    /**
     * Short abbreviation (up to 5 characters, stored uppercase) that identifies the instructor's
     * cells in the Excel timetable. Must be configured before uploading a timetable.
     */
    private String initials;

    /** URL of the Google profile photo, used as the avatar in the UI. */
    private String profilePhotoUrl;

    /**
     * Google OAuth 2.0 access token used to create, update, and delete Google Calendar events.
     * Stored as TEXT due to variable length. Refreshed on every login.
     */
    @Column(columnDefinition = "TEXT")
    private String accessToken;

    /**
     * Google OAuth 2.0 refresh token for obtaining new access tokens without user interaction.
     * Not currently used server-side (implicit flow does not return a refresh token), but
     * reserved for future transition to the authorization-code flow.
     */
    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    /**
     * Expiry timestamp of the current access token.
     * Set to {@code now + 1 hour} on each login, matching Google's default token lifetime.
     */
    private LocalDateTime tokenExpiry;

    /** Timestamp populated automatically by Hibernate when the row is first inserted. */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Timestamp updated automatically by Hibernate on every row modification. */
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
