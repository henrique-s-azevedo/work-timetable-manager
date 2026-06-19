package com.gymtimetable.controller;

import com.gymtimetable.model.Instructor;
import com.gymtimetable.repository.InstructorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final InstructorRepository instructorRepository;

    /**
     * Called right after Google login. Registers or updates instructor.
     * Body: { accessToken, refreshToken, tokenExpiry }
     * Bearer: Google ID token (JWT) — validated by Spring Security
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {

        String googleId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        String picture = jwt.getClaimAsString("picture");
        String accessToken = (String) body.get("accessToken");
        String refreshToken = (String) body.getOrDefault("refreshToken", null);

        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElse(Instructor.builder().googleId(googleId).email(email).build());

        instructor.setName(name);
        if (picture != null && instructor.getProfilePhotoUrl() == null) {
            instructor.setProfilePhotoUrl(picture);
        }
        if (accessToken != null) {
            instructor.setAccessToken(accessToken);
        }
        if (refreshToken != null) {
            instructor.setRefreshToken(refreshToken);
        }
        instructor.setTokenExpiry(LocalDateTime.now().plusHours(1));

        instructorRepository.save(instructor);

        return ResponseEntity.ok(Map.of(
            "id", instructor.getId(),
            "email", instructor.getEmail(),
            "name", instructor.getName() != null ? instructor.getName() : "",
            "initials", instructor.getInitials() != null ? instructor.getInitials() : "",
            "profilePhotoUrl", instructor.getProfilePhotoUrl() != null ? instructor.getProfilePhotoUrl() : ""
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        String googleId = jwt.getSubject();
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));

        return ResponseEntity.ok(toMap(instructor));
    }

    @PatchMapping("/initials")
    public ResponseEntity<Map<String, Object>> updateInitials(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {

        String googleId = jwt.getSubject();
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));

        instructor.setInitials(body.get("initials").strip().toUpperCase());
        instructorRepository.save(instructor);
        return ResponseEntity.ok(toMap(instructor));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {

        String googleId = jwt.getSubject();
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));

        if (body.containsKey("name")) instructor.setName(body.get("name"));
        if (body.containsKey("profilePhotoUrl")) instructor.setProfilePhotoUrl(body.get("profilePhotoUrl"));
        instructorRepository.save(instructor);
        return ResponseEntity.ok(toMap(instructor));
    }

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
