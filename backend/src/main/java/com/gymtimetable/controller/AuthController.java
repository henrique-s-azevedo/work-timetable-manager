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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final InstructorRepository instructorRepository;

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

        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElse(Instructor.builder().googleId(googleId).email(email).build());

        if (name != null) instructor.setName(name);
        if (email != null && instructor.getEmail() == null) instructor.setEmail(email);
        if (picture != null && instructor.getProfilePhotoUrl() == null) instructor.setProfilePhotoUrl(picture);
        if (accessToken != null) instructor.setAccessToken(accessToken);
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

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal String googleId) {
        Instructor instructor = instructorRepository.findByGoogleId(googleId)
            .orElseThrow(() -> new RuntimeException("Instructor not found"));
        return ResponseEntity.ok(toMap(instructor));
    }

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
