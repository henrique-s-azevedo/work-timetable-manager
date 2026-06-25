package com.gymtimetable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Work Timetable Manager application.
 *
 * <p>This Spring Boot application provides a REST API for gym instructors to upload
 * weekly Excel timetables, parse their scheduled sessions, and export them to Google Calendar.
 * It uses PostgreSQL for persistence and Google OAuth 2.0 for authentication.</p>
 */
@SpringBootApplication
public class GymTimetableApplication {

    /**
     * Bootstraps the Spring application context and starts the embedded server.
     *
     * @param args command-line arguments passed to the JVM at startup
     */
    public static void main(String[] args) {
        SpringApplication.run(GymTimetableApplication.class, args);
    }
}
